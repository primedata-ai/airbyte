package io.airbyte.integrations.destination.snowflake.typing_deduping;

import static java.util.stream.Collectors.joining;

import com.google.common.annotations.VisibleForTesting;
import io.airbyte.integrations.base.JavaBaseConstants;
import io.airbyte.integrations.base.destination.typing_deduping.AirbyteProtocolType;
import io.airbyte.integrations.base.destination.typing_deduping.AirbyteType;
import io.airbyte.integrations.base.destination.typing_deduping.Array;
import io.airbyte.integrations.base.destination.typing_deduping.ColumnId;
import io.airbyte.integrations.base.destination.typing_deduping.SqlGenerator;
import io.airbyte.integrations.base.destination.typing_deduping.StreamConfig;
import io.airbyte.integrations.base.destination.typing_deduping.StreamId;
import io.airbyte.integrations.base.destination.typing_deduping.Struct;
import io.airbyte.integrations.base.destination.typing_deduping.TableNotMigratedException;
import io.airbyte.integrations.base.destination.typing_deduping.Union;
import io.airbyte.integrations.base.destination.typing_deduping.UnsupportedOneOf;
import io.airbyte.protocol.models.v0.DestinationSyncMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.text.StringSubstitutor;

public class SnowflakeSqlGenerator implements SqlGenerator<SnowflakeTableDefinition> {

  public static final String QUOTE = "\"";

  private final ColumnId CDC_DELETED_AT_COLUMN = buildColumnId("_ab_cdc_deleted_at");

  @Override
  public StreamId buildStreamId(String namespace, String name, String rawNamespaceOverride) {
    // TODO
    return new StreamId(namespace, name, rawNamespaceOverride, StreamId.concatenateRawTableName(namespace, name), namespace, name);
  }

  @Override
  public ColumnId buildColumnId(String name) {
    // TODO
    return new ColumnId(name, name, name);
  }

  public String toDialectType(final AirbyteType type) {
    if (type instanceof final AirbyteProtocolType p) {
      return toDialectType(p);
    } else if (type instanceof Struct) {
      // TODO should this+array just be VARIANT?
      return "OBJECT";
    } else if (type instanceof Array) {
      return "ARRAY";
    } else if (type instanceof UnsupportedOneOf) {
      return "VARIANT";
    } else if (type instanceof final Union u) {
      final AirbyteType typeWithPrecedence = u.chooseType();
      // typeWithPrecedence is never a Union, so this recursion is safe.
      return toDialectType(typeWithPrecedence);
    }

    // Literally impossible; AirbyteType is a sealed interface.
    throw new IllegalArgumentException("Unsupported AirbyteType: " + type);
  }

  public String toDialectType(final AirbyteProtocolType airbyteProtocolType) {
    // TODO verify these types against normalization
    return switch (airbyteProtocolType) {
      case STRING -> "TEXT";
      case NUMBER -> "FLOAT";
      case INTEGER -> "NUMBER";
      case BOOLEAN -> "BOOLEAN";
      case TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMP_TZ";
      case TIMESTAMP_WITHOUT_TIMEZONE -> "TIMESTAMP_NTZ";
      // If you change this - also change the logic in extractAndCast
      case TIME_WITH_TIMEZONE -> "TEXT";
      case TIME_WITHOUT_TIMEZONE -> "TIME";
      case DATE -> "DATE";
      case UNKNOWN -> "VARIANT";
    };
  }

  @Override
  public String createTable(StreamConfig stream, String suffix) {
    final String columnDeclarations = stream.columns().entrySet().stream()
        .map(column -> column.getKey().name(QUOTE) + " " + toDialectType(column.getValue()))
        .collect(joining(",\n"));
    // TODO indexes and stuff
    return new StringSubstitutor(Map.of(
        "final_namespace", stream.id().finalNamespace(QUOTE),
        "final_table_id", stream.id().finalTableId(QUOTE, suffix),
        "column_declarations", columnDeclarations)).replace(
        """
        CREATE SCHEMA IF NOT EXISTS ${final_namespace};

        CREATE TABLE ${final_table_id} (
          "_airbyte_raw_id" TEXT NOT NULL,
          "_airbyte_extracted_at" TIMESTAMP_TZ NOT NULL,
          "_airbyte_meta" VARIANT NOT NULL,
          ${column_declarations}
        );
        """);
  }

  @Override
  public boolean existingSchemaMatchesStreamConfig(StreamConfig stream, SnowflakeTableDefinition existingTable) throws TableNotMigratedException {
    if (!existingTable.columns().keySet().containsAll(JavaBaseConstants.V2_RAW_TABLE_COLUMN_NAMES)) {
      throw new TableNotMigratedException(String.format("Stream %s has not been migrated to the Destinations V2 format", stream.id().finalName()));
    }

    // Check that the columns match, with special handling for the metadata columns.
    LinkedHashMap<Object, Object> intendedColumns = stream.columns().entrySet().stream()
        .collect(LinkedHashMap::new,
            (map, column) -> map.put(column.getKey().name(), toDialectType(column.getValue())),
            LinkedHashMap::putAll);
    LinkedHashMap<String, String> actualColumns = existingTable.columns().entrySet().stream()
        .filter(column -> !JavaBaseConstants.V2_FINAL_TABLE_METADATA_COLUMNS.contains(column.getKey()))
        .collect(LinkedHashMap::new,
            (map, column) -> map.put(column.getKey(), column.getValue()),
            LinkedHashMap::putAll);
    boolean sameColumns = actualColumns.equals(intendedColumns)
        && "TEXT".equals(existingTable.columns().get(JavaBaseConstants.COLUMN_NAME_AB_RAW_ID))
        && "TIMESTAMP_TZ".equals(existingTable.columns().get(JavaBaseConstants.COLUMN_NAME_AB_EXTRACTED_AT))
        && "VARIANT".equals(existingTable.columns().get(JavaBaseConstants.COLUMN_NAME_DATA));

    return sameColumns;
  }

  @Override
  public String updateTable(StreamConfig stream, String finalSuffix) {
    return updateTable(stream, finalSuffix, true);
  }

  private String updateTable(StreamConfig stream, String finalSuffix, boolean verifyPrimaryKeys) {
    String validatePrimaryKeys = "";
    if (verifyPrimaryKeys && stream.destinationSyncMode() == DestinationSyncMode.APPEND_DEDUP) {
      validatePrimaryKeys = validatePrimaryKeys(stream.id(), stream.primaryKey(), stream.columns());
    }
    final String insertNewRecords = insertNewRecords(stream, finalSuffix, stream.columns());
    String dedupFinalTable = "";
    String cdcDeletes = "";
    String dedupRawTable = "";
    if (stream.destinationSyncMode() == DestinationSyncMode.APPEND_DEDUP) {
      dedupRawTable = dedupRawTable(stream.id(), finalSuffix);
      // If we're in dedup mode, then we must have a cursor
      dedupFinalTable = dedupFinalTable(stream.id(), finalSuffix, stream.primaryKey(), stream.cursor().get());
      cdcDeletes = cdcDeletes(stream, finalSuffix, stream.columns());
    }
    final String commitRawTable = commitRawTable(stream.id());

    return new StringSubstitutor(Map.of(
        "validate_primary_keys", validatePrimaryKeys,
        "insert_new_records", insertNewRecords,
        "dedup_final_table", dedupFinalTable,
        "cdc_deletes", cdcDeletes,
        "dedupe_raw_table", dedupRawTable,
        "commit_raw_table", commitRawTable)).replace(
        """
        BEGIN TRANSACTION;
        ${validate_primary_keys}
        ${insert_new_records}
        ${dedup_final_table}
        ${dedupe_raw_table}
        ${cdc_deletes}
        ${commit_raw_table}
        COMMIT;
        """);
  }

  private String extractAndCast(final ColumnId column, final AirbyteType airbyteType) {
    if (airbyteType instanceof final Union u) {
      // This is guaranteed to not be a Union, so we won't recurse infinitely
      final AirbyteType chosenType = u.chooseType();
      return extractAndCast(column, chosenType);
    } else if (airbyteType == AirbyteProtocolType.TIME_WITH_TIMEZONE) {
      // We're using TEXT for this type, so need to explicitly check the string format.
      // There's a bunch of ways we could do this; this regex is approximately correct and easy to implement.
      return new StringSubstitutor(Map.of("column_name", column.originalName())).replace(
          """
          CASE
            WHEN NOT ("_airbyte_data":"${column_name}"::TEXT REGEXP '\\\\d{1,2}:\\\\d{2}:\\\\d{2}(\\\\.\\\\d+)?(Z|[+\\\\-]\\\\d{1,2}:\\\\d{2})')
              THEN NULL
            ELSE "_airbyte_data":"${column_name}"
          END
          """);
    } else {
      final String dialectType = toDialectType(airbyteType);
      return switch (dialectType) {
        // try_cast doesn't support variant/array/object, so handle them specially
        case "VARIANT" -> "\"_airbyte_data\":\"" + column.originalName() + "\"";
        // We need to validate that the struct is actually a struct.
        // Note that struct columns are actually nullable in two ways. For a column `foo`:
        // {foo: null} and {} are both valid, and are both written to the final table as a SQL NULL (_not_ a
        // JSON null).
        case "OBJECT" -> new StringSubstitutor(Map.of("column_name", column.originalName())).replace(
            """
            CASE
              WHEN TYPEOF("_airbyte_data":"${column_name}") != 'OBJECT'
                THEN NULL
              ELSE "_airbyte_data":"${column_name}"
            END
            """);
        // Much like the object case, arrays need special handling.
        case "ARRAY" -> new StringSubstitutor(Map.of("column_name", column.originalName())).replace(
            """
            CASE
              WHEN TYPEOF("_airbyte_data":"${column_name}") != 'ARRAY'
                THEN NULL
              ELSE "_airbyte_data":"${column_name}"
            END
            """);
        default -> "TRY_CAST(\"_airbyte_data\":\"" + column.originalName() + "\"::text as " + dialectType + ")";
      };
    }
  }

  @VisibleForTesting
  String validatePrimaryKeys(final StreamId id,
                             final List<ColumnId> primaryKeys,
                             final LinkedHashMap<ColumnId, AirbyteType> streamColumns) {
    final String pkNullChecks = primaryKeys.stream().map(
        pk -> {
          final String jsonExtract = extractAndCast(pk, streamColumns.get(pk));
          return "AND " + jsonExtract + " IS NULL";
        }).collect(joining("\n"));

    return new StringSubstitutor(Map.of(
        "raw_table_id", id.rawTableId(QUOTE),
        "pk_null_checks", pkNullChecks)).replace(
            // Wrap this inside a script block so that we can use the scripting language
        """
        EXECUTE IMMEDIATE $$
        BEGIN
        LET missing_pk_count INTEGER := (
          SELECT COUNT(1)
          FROM ${raw_table_id}
          WHERE
            "_airbyte_loaded_at" IS NULL
            ${pk_null_checks}
        );

        IF (missing_pk_count > 0) THEN
          RAISE STATEMENT_ERROR;
        END IF;
        RETURN 'SUCCESS';
        END;
        $$;
        """);
  }

  @VisibleForTesting
  String insertNewRecords(final StreamConfig stream, final String finalSuffix, final LinkedHashMap<ColumnId, AirbyteType> streamColumns) {
    final String columnCasts = streamColumns.entrySet().stream().map(
            col -> extractAndCast(col.getKey(), col.getValue()) + " as " + col.getKey().name(QUOTE) + ",")
        .collect(joining("\n"));
    final String columnErrors = streamColumns.entrySet().stream().map(
        col -> new StringSubstitutor(Map.of(
            "raw_col_name", col.getKey().originalName(),
            "col_type", toDialectType(col.getValue()),
            "json_extract", extractAndCast(col.getKey(), col.getValue()))).replace(
            // TYPEOF returns "NULL_VALUE" for a JSON null and "NULL" for a SQL null
            """
                CASE
                  WHEN (TYPEOF("_airbyte_data":"${raw_col_name}") NOT IN ('NULL', 'NULL_VALUE'))
                    AND (${json_extract} IS NULL)
                    THEN ['Problem with `${raw_col_name}`']
                  ELSE []
                END"""))
        .reduce(
            "ARRAY_CONSTRUCT()",
            (acc, col) -> "ARRAY_CAT(" + acc + ", " + col + ")"
        );
    final String columnList = streamColumns.keySet().stream().map(quotedColumnId -> quotedColumnId.name(QUOTE) + ",").collect(joining("\n"));

    String cdcConditionalOrIncludeStatement = "";
    if (stream.destinationSyncMode() == DestinationSyncMode.APPEND_DEDUP && streamColumns.containsKey(CDC_DELETED_AT_COLUMN)) {
      cdcConditionalOrIncludeStatement = """
      OR (
        "_airbyte_loaded_at" IS NOT NULL
        AND "_airbyte_data":"_ab_cdc_deleted_at" IS NOT NULL
      )
      """;
    }

    return new StringSubstitutor(Map.of(
        "raw_table_id", stream.id().rawTableId(QUOTE),
        "final_table_id", stream.id().finalTableId(QUOTE, finalSuffix),
        "column_casts", columnCasts,
        "column_errors", columnErrors,
        "cdcConditionalOrIncludeStatement", cdcConditionalOrIncludeStatement,
        "column_list", columnList)).replace(
        """
            INSERT INTO ${final_table_id}
            (
            ${column_list}
              "_airbyte_meta",
              "_airbyte_raw_id",
              "_airbyte_extracted_at"
            )
            WITH intermediate_data AS (
              SELECT
            ${column_casts}
            ${column_errors} as "_airbyte_cast_errors",
              "_airbyte_raw_id",
              "_airbyte_extracted_at"
              FROM ${raw_table_id}
              WHERE
                "_airbyte_loaded_at" IS NULL
                ${cdcConditionalOrIncludeStatement}
            )
            SELECT
            ${column_list}
              OBJECT_CONSTRUCT('errors', "_airbyte_cast_errors") AS "_airbyte_meta",
              "_airbyte_raw_id",
              "_airbyte_extracted_at"
            FROM intermediate_data;""");
  }

  @VisibleForTesting
  String dedupFinalTable(final StreamId id,
                         final String finalSuffix,
                         final List<ColumnId> primaryKey,
                         final ColumnId cursor) {
    final String pkList = primaryKey.stream().map(columnId -> columnId.name(QUOTE)).collect(joining(","));

    return new StringSubstitutor(Map.of(
        "final_table_id", id.finalTableId(QUOTE, finalSuffix),
        "pk_list", pkList,
        "cursor_name", cursor.name(QUOTE))
    ).replace(
        """
        DELETE FROM ${final_table_id}
        WHERE "_airbyte_raw_id" IN (
          SELECT "_airbyte_raw_id" FROM (
            SELECT "_airbyte_raw_id", row_number() OVER (
              PARTITION BY ${pk_list} ORDER BY ${cursor_name} DESC NULLS LAST, "_airbyte_extracted_at" DESC
            ) as row_number FROM ${final_table_id}
          )
          WHERE row_number != 1
        );
        """);
  }

  @VisibleForTesting
  String cdcDeletes(final StreamConfig stream,
                    final String finalSuffix,
                    final LinkedHashMap<ColumnId, AirbyteType> streamColumns) {

    if (stream.destinationSyncMode() != DestinationSyncMode.APPEND_DEDUP){
      return "";
    }

    if (!streamColumns.containsKey(CDC_DELETED_AT_COLUMN)){
      return "";
    }

    final String pkList = stream.primaryKey().stream().map(columnId -> columnId.name(QUOTE)).collect(joining(","));
    final String pkCasts = stream.primaryKey().stream().map(pk -> extractAndCast(pk, streamColumns.get(pk))).collect(joining(",\n"));

    // we want to grab IDs for deletion from the raw table (not the final table itself) to hand out-of-order record insertions after the delete has been registered
    return new StringSubstitutor(Map.of(
        "final_table_id", stream.id().finalTableId(QUOTE, finalSuffix),
        "raw_table_id", stream.id().rawTableId(QUOTE),
        "pk_list", pkList,
        "pk_extracts", pkCasts,
        "quoted_cdc_delete_column", QUOTE + "_ab_cdc_deleted_at" + QUOTE)
    ).replace(
        """
        DELETE FROM ${final_table_id}
        WHERE ARRAY_CONSTRUCT(${pk_list}) IN (
          SELECT ARRAY_CONSTRUCT(
              ${pk_extracts}
            )
          FROM  ${raw_table_id}
          WHERE "_airbyte_data":"_ab_cdc_deleted_at" != 'null'
        );
        """
    );
  }

  @VisibleForTesting
  String dedupRawTable(final StreamId id, final String finalSuffix) {
    return new StringSubstitutor(Map.of(
        "raw_table_id", id.rawTableId(QUOTE),
        "final_table_id", id.finalTableId(QUOTE, finalSuffix))).replace(
        // Note that this leaves _all_ deletion records in the raw table. We _could_ clear them out, but it
        // would be painful,
        // and it only matters in a few edge cases.
        """
        DELETE FROM ${raw_table_id}
        WHERE "_airbyte_raw_id" NOT IN (
          SELECT "_airbyte_raw_id" FROM ${final_table_id}
        );
        """);
  }

  @VisibleForTesting
  String commitRawTable(final StreamId id) {
    return new StringSubstitutor(Map.of(
        "raw_table_id", id.rawTableId(QUOTE))).replace(
        """
        UPDATE ${raw_table_id}
        SET "_airbyte_loaded_at" = CURRENT_TIMESTAMP()
        WHERE "_airbyte_loaded_at" IS NULL
        ;""");
  }

  @Override
  public String overwriteFinalTable(StreamId stream, String finalSuffix) {
    return new StringSubstitutor(Map.of(
        "final_table", stream.finalTableId(QUOTE),
        "tmp_final_table", stream.finalTableId(QUOTE, finalSuffix))).replace(
            """
                BEGIN TRANSACTION;
                DROP TABLE IF EXISTS ${final_table};
                ALTER TABLE ${tmp_final_table} RENAME TO ${final_table};
                COMMIT;
                """
        );
  }

  @Override
  public String softReset(StreamConfig stream) {
    String createTempTable = createTable(stream, SOFT_RESET_SUFFIX);
    String clearLoadedAt = clearLoadedAt(stream.id());
    final String rebuildInTempTable = updateTable(stream, SOFT_RESET_SUFFIX, false);
    final String overwriteFinalTable = overwriteFinalTable(stream.id(), SOFT_RESET_SUFFIX);
    return String.join("\n", createTempTable, clearLoadedAt, rebuildInTempTable, overwriteFinalTable);
  }

  private String clearLoadedAt(final StreamId streamId) {
    return new StringSubstitutor(Map.of("raw_table_id", streamId.rawTableId(QUOTE)))
        .replace("""
            UPDATE ${raw_table_id} SET "_airbyte_loaded_at" = NULL;
            """);
  }

}
