package fr.aphp.wind.eds.generator.target.eds

import java.util.UUID

import fr.aphp.wind.eds.generator.source.synthea.SyntheaDataBundle
import org.apache.spark.sql.{Column, DataFrame, SparkSession}

object FromSynthea {
  def apply(bundle: SyntheaDataBundle): EDSDataBundle = {
    EDSDataBundle(
      persons = convert.syntheaPatients.toPersons(bundle.patients),
      observations = convert.syntheaPatients.toObservations(bundle.patients),
      visitOccurrences = convert.syntheaEncounters.toVisitOccurrences(bundle.encounters),
      careSites = convert.syntheaOrganizations.toCareSites(bundle.organizations),
      conditionOccurrences = convert.syntheaConditions.toConditionOccurrences(bundle.conditions),
      procedureOccurrences = convert.syntheaProcedures.toProcedureOccurrences(bundle.procedures),
      providers = convert.syntheaProviders.toProviders(bundle.providers)
    )
  }

  private object convert {
    private lazy val sqlContext = SparkSession.active.sqlContext

    import org.apache.spark.sql.functions._
    import sqlContext.implicits._

    private val omopId = udf((stringId: String) => {
      // NOTE: UUIDs are 128 bits, but long values are 64.  We only use the
      // least significant bits of the UUIDs, which _might_ give collisions.
      // One way to guard against collisions would be to ensure uniqueness after all the
      // tables are generated.
      UUID.fromString(stringId).getLeastSignificantBits
    })

    private def fromSyntheaDatetime(column: Column): Column = {
      to_timestamp(column, "yyyy-MM-dd'T'HH:mm'Z'")
    }

    private def fromSyntheaDate(column: Column): Column = {
      to_date(column, "YYYY-MM-DD")
    }

    private def fromSyntheaDateToTimestamp(column: Column): Column = {
      to_timestamp(column, "yyyy-MM-dd")
    }

    object syntheaPatients {
      def toPersons(df: DataFrame): DataFrame = {
        df.select("id", "gender", "birthdate", "deathdate")
          .withColumn("person_id", omopId('id))
          .withColumn("gender_concept_id",
            when('gender === "M", 8507L)
              .when('gender === "F", 8532L))
          .withColumn("birth_datetime", fromSyntheaDateToTimestamp('birthdate))
          .withColumn("death_datetime", fromSyntheaDateToTimestamp('deathdate))
          .select("person_id", "gender_concept_id", "birth_datetime", "death_datetime")
      }

      def toObservations(df: DataFrame): DataFrame = {
        Map("first" -> 3042942L, "last" -> 3046810L, "ssn" -> 398093005L)
          .map { case (syntheaColumn, conceptId) =>
            df.select("id", syntheaColumn)
              .where('first.isNotNull)
              .withColumn("observation_concept_id", typedLit(conceptId))
              .withColumn("person_id", omopId('id))
              .withColumnRenamed(syntheaColumn, "value_as_string")
              .drop("id")
          }
          .reduce {
            _ union _
          }
      }
    }

    object syntheaEncounters {
      def toVisitOccurrences(df: DataFrame): DataFrame = {
        df.select("id", "patient", "provider", "organization", "start", "stop", "encounterClass")
          .withColumn("visit_occurrence_id", omopId('id))
          .withColumn("person_id", omopId('patient))
          .withColumn("provider_id", omopId('provider))
          .withColumn("care_site_id", omopId('organization))
          .withColumn("visit_start_datetime", fromSyntheaDatetime('start))
          .withColumn("visit_end_datetime", fromSyntheaDatetime('stop))
          .withColumn("visit_concept_id",
            when('encounterClass === "inpatient", 9201L)
              .when('encounterClass === "ambulatory" || 'encounterClass === "outpatient"
                || 'encounterClass === "wellness", 9202L)
              .when('encounterClass === "emergency" || 'encounterClass === "urgentcare", 9203))
          .withColumn("visit_type_concept_id", lit(44818517L))
          .select(
            "visit_occurrence_id", "person_id", "provider_id", "care_site_id",
            "visit_start_datetime", "visit_end_datetime", "visit_concept_id", "visit_type_concept_id"
          )
      }
    }

    object syntheaOrganizations {
      def toCareSites(df: DataFrame): DataFrame = {
        df.select("id", "name")
          .withColumn("care_site_id", omopId('id))
          .withColumn("care_site_name", 'name)
          .select("care_site_id", "care_site_name")
      }
    }

    object syntheaConditions {
      def toConditionOccurrences(df: DataFrame): DataFrame = {
        import fr.aphp.wind.eds.generator.uuidLong

        df.select("start", "stop", "encounter", "patient", "code")
          .withColumn("condition_occurrence_id", uuidLong)
          .withColumn("condition_start_datetime", fromSyntheaDateToTimestamp('start))
          .withColumn("condition_end_datetime", fromSyntheaDateToTimestamp('stop))
          .drop("start", "stop")
          .withColumn("visit_occurrence_id", omopId('encounter))
          .withColumn("person_id", omopId('patient))
          .drop("encounter", "patient")
          .withColumnRenamed("code", "condition_source_value")
          .withColumn("condition_type_source_value", lit("SNOMED"))
      }
    }

    object syntheaProcedures {
      def toProcedureOccurrences(df: DataFrame): DataFrame = {
        import fr.aphp.wind.eds.generator.uuidLong

        df.select( "code", "date", "patient", "encounter")
          .withColumn("procedure_occurrence_id", uuidLong)
          .withColumnRenamed("code", "procedure_source_value")
          .withColumn("procedure_type_source_value", lit("SNOMED"))
          .withColumn("procedure_datetime", fromSyntheaDatetime('date))
          .drop("date")
          .withColumn("person_id", omopId('patient))
          .withColumn("visit_occurrence_id", omopId('encounter))
          .drop("patient", "encounter")
      }
    }

    object syntheaProviders {
      def toProviders(df: DataFrame): DataFrame = {
        df.select("id", "name", "organization", "gender")
          .withColumn("provider_id", omopId('id))
          .withColumnRenamed("name", "provider_name")
          .withColumn("provider_source_value", 'id)
          .withColumn("npi", 'id)
          .withColumn("dea", 'id)
          .drop("id")
          .withColumn("care_site_id", omopId('organization))
          .drop("organization")
          .withColumn("is_active", lit(true))
          .withColumn("gender_concept_id",
            when('gender === "M", 8507L)
              .when('gender === "F", 8532L))
          .drop("gender")
      }
    }

  }

}
