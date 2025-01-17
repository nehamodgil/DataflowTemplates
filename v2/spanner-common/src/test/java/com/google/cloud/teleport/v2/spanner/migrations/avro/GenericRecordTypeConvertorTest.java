/*
 * Copyright (C) 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.v2.spanner.migrations.avro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.cloud.ByteArray;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.Dialect;
import com.google.cloud.spanner.Value;
import com.google.cloud.teleport.v2.spanner.ddl.Ddl;
import com.google.cloud.teleport.v2.spanner.exceptions.InvalidTransformationException;
import com.google.cloud.teleport.v2.spanner.migrations.schema.ISchemaMapper;
import com.google.cloud.teleport.v2.spanner.migrations.schema.IdentityMapper;
import com.google.cloud.teleport.v2.spanner.migrations.schema.SessionBasedMapper;
import com.google.cloud.teleport.v2.spanner.type.Type;
import com.google.common.io.Resources;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.Test;
import org.mockito.Mockito;

public class GenericRecordTypeConvertorTest {

  public Schema getLogicalTypesSchema() {
    // Create schema types with LogicalTypes
    Schema dateType = LogicalTypes.date().addToSchema(Schema.create(Schema.Type.INT));
    Schema decimalType = LogicalTypes.decimal(4, 2).addToSchema(Schema.create(Schema.Type.BYTES));
    Schema timeMicrosType = LogicalTypes.timeMicros().addToSchema(Schema.create(Schema.Type.LONG));
    Schema timeMillisType = LogicalTypes.timeMillis().addToSchema(Schema.create(Schema.Type.INT));
    Schema timestampMicrosType =
        LogicalTypes.timestampMicros().addToSchema(Schema.create(Schema.Type.LONG));
    Schema timestampMillisType =
        LogicalTypes.timestampMillis().addToSchema(Schema.create(Schema.Type.LONG));
    Schema jsonType =
        new LogicalType(GenericRecordTypeConvertor.CustomAvroTypes.JSON)
            .addToSchema(SchemaBuilder.builder().stringType());
    Schema numberType =
        new LogicalType(GenericRecordTypeConvertor.CustomAvroTypes.NUMBER)
            .addToSchema(SchemaBuilder.builder().stringType());
    Schema varcharType =
        new LogicalType(GenericRecordTypeConvertor.CustomAvroTypes.VARCHAR)
            .addToSchema(SchemaBuilder.builder().stringType());
    Schema timeIntervalType =
        new LogicalType(GenericRecordTypeConvertor.CustomAvroTypes.TIME_INTERVAL)
            .addToSchema(SchemaBuilder.builder().longType());
    Schema unsupportedType =
        new LogicalType(GenericRecordTypeConvertor.CustomAvroTypes.UNSUPPORTED)
            .addToSchema(SchemaBuilder.builder().nullType());

    // Build the schema using the created types
    return SchemaBuilder.record("logicalTypes")
        .namespace("com.test.schema")
        .fields()
        .name("date_col")
        .type(dateType)
        .noDefault()
        .name("decimal_col")
        .type(decimalType)
        .noDefault()
        .name("time_micros_col")
        .type(timeMicrosType)
        .noDefault()
        .name("time_millis_col")
        .type(timeMillisType)
        .noDefault()
        .name("timestamp_micros_col")
        .type(timestampMicrosType)
        .noDefault()
        .name("timestamp_millis_col")
        .type(timestampMillisType)
        .noDefault()
        .name("json_col")
        .type(jsonType)
        .noDefault()
        .name("number_col")
        .type(numberType)
        .noDefault()
        .name("varchar_col")
        .type(varcharType)
        .noDefault()
        .name("time_interval_col")
        .type(timeIntervalType)
        .noDefault()
        .name("unsupported_col")
        .type(unsupportedType)
        .noDefault()
        .endRecord();
  }

  public Schema unionNullType(Schema schema) {
    return SchemaBuilder.builder().unionOf().nullType().and().type(schema).endUnion();
  }

  public Schema getAllSpannerTypesSchema() {
    Schema decimalType =
        unionNullType(LogicalTypes.decimal(5, 2).addToSchema(Schema.create(Schema.Type.BYTES)));
    Schema dateType =
        unionNullType(LogicalTypes.date().addToSchema(Schema.create(Schema.Type.INT)));
    Schema timestampType =
        unionNullType(LogicalTypes.timestampMicros().addToSchema(Schema.create(Schema.Type.LONG)));
    return SchemaBuilder.record("all_types")
        .namespace("com.test.schema")
        .fields()
        .name("bool_col")
        .type(unionNullType(Schema.create(Schema.Type.BOOLEAN)))
        .noDefault()
        .name("int_col")
        .type(unionNullType(Schema.create(Schema.Type.LONG)))
        .noDefault()
        .name("float_col")
        .type(unionNullType(Schema.create(Schema.Type.DOUBLE)))
        .noDefault()
        .name("string_col")
        .type(unionNullType(Schema.create(Schema.Type.STRING)))
        .noDefault()
        .name("numeric_col")
        .type(decimalType)
        .noDefault()
        .name("bytes_col")
        .type(unionNullType(Schema.create(Schema.Type.BYTES)))
        .noDefault()
        .name("timestamp_col")
        .type(timestampType)
        .noDefault()
        .name("date_col")
        .type(dateType)
        .noDefault()
        .endRecord();
  }

  @Test
  public void testHandleLogicalFieldType() {
    Schema avroSchema = getLogicalTypesSchema();

    GenericRecord genericRecord = new GenericData.Record(avroSchema);
    genericRecord.put("date_col", 738991);
    genericRecord.put(
        "decimal_col", ByteBuffer.wrap(new BigDecimal("12.34").unscaledValue().toByteArray()));
    genericRecord.put("time_micros_col", 48035000000L);
    genericRecord.put("time_millis_col", 48035000);
    genericRecord.put("timestamp_micros_col", 1602599400056483L);
    genericRecord.put("timestamp_millis_col", 1602599400056L);
    genericRecord.put("json_col", "{\"k1\":\"v1\"}");
    genericRecord.put("number_col", "289452");
    genericRecord.put("varchar_col", "Hellogcds");
    genericRecord.put("time_interval_col", -3020398999999L);
    genericRecord.put("unsupported_col", null);

    String col = "date_col";
    String result =
        GenericRecordTypeConvertor.handleLogicalFieldType(
            col, genericRecord.get(col), genericRecord.getSchema().getField(col).schema());
    assertEquals("Test date_col conversion: ", "3993-04-16", result);

    col = "decimal_col";
    result =
        GenericRecordTypeConvertor.handleLogicalFieldType(
            col, genericRecord.get(col), genericRecord.getSchema().getField(col).schema());
    assertEquals("Test decimal_col conversion: ", "12.34", result);

    col = "time_micros_col";
    result =
        GenericRecordTypeConvertor.handleLogicalFieldType(
            col, genericRecord.get(col), genericRecord.getSchema().getField(col).schema());
    assertEquals("Test time_micros_col conversion: ", "13:20:35", result);

    col = "time_millis_col";
    result =
        GenericRecordTypeConvertor.handleLogicalFieldType(
            col, genericRecord.get(col), genericRecord.getSchema().getField(col).schema());
    assertEquals("Test time_millis_col conversion: ", "13:20:35", result);

    col = "timestamp_micros_col";
    result =
        GenericRecordTypeConvertor.handleLogicalFieldType(
            col, genericRecord.get(col), genericRecord.getSchema().getField(col).schema());
    assertEquals("Test timestamp_micros_col conversion: ", "2020-10-13T14:30:00.056483Z", result);

    col = "timestamp_millis_col";
    result =
        GenericRecordTypeConvertor.handleLogicalFieldType(
            col, genericRecord.get(col), genericRecord.getSchema().getField(col).schema());
    assertEquals("Test timestamp_millis_col conversion: ", "2020-10-13T14:30:00.056Z", result);

    col = "json_col";
    result =
        GenericRecordTypeConvertor.handleLogicalFieldType(
            col, genericRecord.get(col), genericRecord.getSchema().getField(col).schema());
    assertEquals("Test json_col conversion: ", "{\"k1\":\"v1\"}", result);

    col = "number_col";
    result =
        GenericRecordTypeConvertor.handleLogicalFieldType(
            col, genericRecord.get(col), genericRecord.getSchema().getField(col).schema());
    assertEquals("Test number_col conversion: ", "289452", result);

    col = "varchar_col";
    result =
        GenericRecordTypeConvertor.handleLogicalFieldType(
            col, genericRecord.get(col), genericRecord.getSchema().getField(col).schema());
    assertEquals("Test varchar_col conversion: ", "Hellogcds", result);

    col = "time_interval_col";
    result =
        GenericRecordTypeConvertor.handleLogicalFieldType(
            col, genericRecord.get(col), genericRecord.getSchema().getField(col).schema());
    assertEquals("Test time_interval_col conversion: ", "-838:59:58.999999", result);

    col = "unsupported_col";
    result =
        GenericRecordTypeConvertor.handleLogicalFieldType(
            col, genericRecord.get(col), genericRecord.getSchema().getField(col).schema());
    assertEquals("Test unsupported_col conversion: ", null, result);
  }

  @Test
  public void testTimestampLogicalTypeLimits() {
    Map<Long, String> testCases = new HashMap<>();
    testCases.put(1602599400056483L, "2020-10-13T14:30:00.056483Z"); // Original case
    testCases.put(-62135596800000000L, "0001-01-01T00:00:00Z"); // Min timestamp
    testCases.put(253402300799999999L, "9999-12-31T23:59:59.999999Z"); // Max timestamp
    testCases.put(-30610224000000000L, "1000-01-01T00:00:00Z");
    testCases.put(253402214400000000L, "9999-12-31T00:00:00Z");
    testCases.put(70678915200000000L, "4209-09-23T00:00:00Z");
    testCases.put(96038611200000000L, "5013-05-06T00:00:00Z");
    testCases.put(-47749305600000000L, "0456-11-19T00:00:00Z");
    testCases.put(-62098444800000000L, "0002-03-07T00:00:00Z");
    testCases.put(56074229063257557L, "3746-12-03T06:44:23.257557Z");
    testCases.put(-57549851159000000L, "0146-04-26T18:14:01Z");

    for (Map.Entry<Long, String> entry : testCases.entrySet()) {
      String result =
          GenericRecordTypeConvertor.handleLogicalFieldType(
              "timestamp_micros_col",
              entry.getKey(),
              LogicalTypes.timestampMicros().addToSchema(Schema.create(Schema.Type.LONG)));
      assertEquals("Test timestamp for epoch " + entry.getKey() + ": ", entry.getValue(), result);
    }
  }

  @Test
  public void testHandleLogicalFieldType_nullInput() {
    assertNull(
        GenericRecordTypeConvertor.handleLogicalFieldType(
            "col", null, SchemaBuilder.builder().stringType()));
  }

  @Test(expected = UnsupportedOperationException.class)
  public void testHandleLogicalFieldType_unsupportedLogicalType() {
    GenericRecordTypeConvertor.handleLogicalFieldType(
        "col", "test", SchemaBuilder.builder().stringType());
  }

  @Test
  public void testHandleRecordFieldType() {
    // Tests for timestampTz type.
    String result =
        GenericRecordTypeConvertor.handleRecordFieldType(
            "timestamp_with_time_zone_column",
            AvroTestingHelper.createTimestampTzRecord(1602599400056483L, 3600000),
            AvroTestingHelper.TIMESTAMPTZ_SCHEMA);
    assertEquals("Test timestampTz conversion: ", "2020-10-13T14:30:00.056483Z", result);

    // Tests for datetime type.
    result =
        GenericRecordTypeConvertor.handleRecordFieldType(
            "date_time_column",
            AvroTestingHelper.createDatetimeRecord(738991, 48035000000L),
            AvroTestingHelper.DATETIME_SCHEMA);
    assertEquals("Test datetime conversion: ", "3993-04-16T13:20:35Z", result);

    // Tests for interval type.
    result =
        GenericRecordTypeConvertor.handleRecordFieldType(
            "interval_column",
            AvroTestingHelper.createIntervalRecord(0, 12, 3590123456L),
            AvroTestingHelper.INTERVAL_SCHEMA);
    assertEquals("Test #1 interval conversion:", "12:59:50.123456", result);

    result =
        GenericRecordTypeConvertor.handleRecordFieldType(
            "interval_column",
            AvroTestingHelper.createIntervalRecord(0, -12, 3590000000L),
            AvroTestingHelper.INTERVAL_SCHEMA);
    assertEquals("Test #2 interval conversion:", "-12:59:50", result);
    // Test for interval type with micros greater than permitted limit.
    assertThrows(
        "Test #3 interval conversion:",
        IllegalArgumentException.class,
        () ->
            GenericRecordTypeConvertor.handleRecordFieldType(
                "interval_column",
                AvroTestingHelper.createIntervalRecord(0, 12, 3600000000L),
                AvroTestingHelper.INTERVAL_SCHEMA));

    // Test for unsupported type.
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            GenericRecordTypeConvertor.handleRecordFieldType(
                "unsupported_type_column",
                new GenericData.Record(AvroTestingHelper.UNSUPPORTED_SCHEMA),
                AvroTestingHelper.UNSUPPORTED_SCHEMA));
  }

  @Test
  public void testHandleRecordFieldType_nullInput() {
    assertNull(
        GenericRecordTypeConvertor.handleRecordFieldType(
            "col", null, SchemaBuilder.builder().stringType()));
  }

  static Ddl getIdentityDdl() {
    /* Creates DDL without any schema transformations.
     */
    Ddl ddl =
        Ddl.builder(Dialect.GOOGLE_STANDARD_SQL)
            .createTable("all_types")
            .column("bool_col")
            .bool()
            .notNull()
            .endColumn()
            .column("int_col")
            .int64()
            .notNull()
            .endColumn()
            .column("float_col")
            .float64()
            .endColumn()
            .column("string_col")
            .string()
            .size(10)
            .endColumn()
            .column("numeric_col")
            .numeric()
            .endColumn()
            .column("bytes_col")
            .bytes()
            .endColumn()
            .column("timestamp_col")
            .timestamp()
            .endColumn()
            .column("date_col")
            .date()
            .endColumn()
            .primaryKey()
            .asc("int_col")
            .end()
            .endTable()
            .build();
    return ddl;
  }

  @Test
  public void transformChangeEventTest_identityMapper() throws InvalidTransformationException {
    GenericRecord genericRecord = new GenericData.Record(getAllSpannerTypesSchema());
    genericRecord.put("bool_col", true);
    genericRecord.put("int_col", 10);
    genericRecord.put("float_col", 10.34);
    genericRecord.put("string_col", "hello");
    genericRecord.put(
        "numeric_col", ByteBuffer.wrap(new BigDecimal("12.34").unscaledValue().toByteArray()));
    genericRecord.put("bytes_col", ByteBuffer.wrap(new byte[] {10, 20, 30}));
    genericRecord.put("timestamp_col", 1602599400056483L);
    genericRecord.put("date_col", 738991);
    GenericRecordTypeConvertor genericRecordTypeConvertor =
        new GenericRecordTypeConvertor(new IdentityMapper(getIdentityDdl()), "", null, null);
    Map<String, Value> actual =
        genericRecordTypeConvertor.transformChangeEvent(genericRecord, "all_types");
    Map<String, Value> expected =
        Map.of(
            "bool_col", Value.bool(true),
            "int_col", Value.int64(10),
            "float_col", Value.float64(10.34),
            "string_col", Value.string("hello"),
            "numeric_col", Value.numeric(new BigDecimal("12.340000000")),
            "bytes_col", Value.bytes(ByteArray.copyFrom(new byte[] {10, 20, 30})),
            "timestamp_col",
                Value.timestamp(Timestamp.parseTimestamp("2020-10-13T14:30:00.056483Z")),
            "date_col", Value.date(com.google.cloud.Date.parseDate("3993-04-16")));
    assertEquals(expected, actual);
  }

  @Test
  public void transformChangeEventTest_nullValues() throws InvalidTransformationException {
    GenericRecord genericRecord = new GenericData.Record(getAllSpannerTypesSchema());
    genericRecord.put("bool_col", null);
    genericRecord.put("int_col", null);
    genericRecord.put("float_col", null);
    genericRecord.put("string_col", null);
    genericRecord.put("numeric_col", null);
    genericRecord.put("bytes_col", null);
    genericRecord.put("timestamp_col", null);
    genericRecord.put("date_col", null);
    GenericRecordTypeConvertor genericRecordTypeConvertor =
        new GenericRecordTypeConvertor(new IdentityMapper(getIdentityDdl()), "", null, null);
    Map<String, Value> actual =
        genericRecordTypeConvertor.transformChangeEvent(genericRecord, "all_types");
    Map<String, Value> expected =
        Map.of(
            "bool_col",
            Value.bool(null),
            "int_col",
            Value.int64(null),
            "float_col",
            Value.float64(null),
            "string_col",
            Value.string(null),
            "numeric_col",
            Value.numeric(null),
            "bytes_col",
            Value.bytes(null),
            "timestamp_col",
            Value.timestamp(null),
            "date_col",
            Value.date(null));
    assertEquals(expected, actual);
  }

  @Test
  public void transformChangeEventTest_illegalUnionType() {
    GenericRecordTypeConvertor genericRecordTypeConvertor =
        new GenericRecordTypeConvertor(new IdentityMapper(getIdentityDdl()), "", null, null);
    Schema schema =
        SchemaBuilder.builder()
            .unionOf()
            .nullType()
            .and()
            .type(Schema.create(Schema.Type.BOOLEAN))
            .and()
            .type(Schema.create(Schema.Type.STRING))
            .endUnion();
    assertThrows(
        IllegalArgumentException.class,
        () -> genericRecordTypeConvertor.getSpannerValue(null, schema, "union_col", Type.string()));
  }

  @Test(expected = IllegalArgumentException.class)
  public void transformChangeEventTest_incorrectSpannerType()
      throws InvalidTransformationException {

    ISchemaMapper mockSchemaMapper = mock(ISchemaMapper.class);
    when(mockSchemaMapper.getDialect()).thenReturn(Dialect.GOOGLE_STANDARD_SQL);
    when(mockSchemaMapper.getSpannerTableName(anyString(), anyString())).thenReturn("test");
    when(mockSchemaMapper.getSpannerColumns(anyString(), anyString()))
        .thenReturn(List.of("bool_col"));
    when(mockSchemaMapper.getSourceColumnName(anyString(), anyString(), anyString()))
        .thenReturn("bool_col");
    when(mockSchemaMapper.getSpannerColumnType(anyString(), anyString(), anyString()))
        .thenReturn(Type.array(Type.bool()));
    when(mockSchemaMapper.colExistsAtSource(anyString(), anyString(), anyString()))
        .thenReturn(true);

    GenericRecord genericRecord = new GenericData.Record(getAllSpannerTypesSchema());
    genericRecord.put("bool_col", true);
    GenericRecordTypeConvertor genericRecordTypeConvertor =
        new GenericRecordTypeConvertor(mockSchemaMapper, "", null, null);

    genericRecordTypeConvertor.transformChangeEvent(genericRecord, "all_types");
  }

  @Test
  public void transformChangeEventTest_nullDialect() {
    ISchemaMapper mockSchemaMapper = mock(ISchemaMapper.class);
    when(mockSchemaMapper.getDialect()).thenReturn(null);
    when(mockSchemaMapper.getSpannerTableName(anyString(), anyString())).thenReturn("test");
    when(mockSchemaMapper.getSpannerColumns(anyString(), anyString()))
        .thenReturn(List.of("bool_col"));
    when(mockSchemaMapper.getSourceColumnName(anyString(), anyString(), anyString()))
        .thenReturn("bool_col");
    when(mockSchemaMapper.getSpannerColumnType(anyString(), anyString(), anyString()))
        .thenReturn(Type.array(Type.bool()));
    when(mockSchemaMapper.colExistsAtSource(anyString(), anyString(), anyString()))
        .thenReturn(true);

    GenericRecord genericRecord = new GenericData.Record(getAllSpannerTypesSchema());
    genericRecord.put("bool_col", true);
    GenericRecordTypeConvertor genericRecordTypeConvertor =
        new GenericRecordTypeConvertor(mockSchemaMapper, "", null, null);

    assertThrows(
        NullPointerException.class,
        () -> genericRecordTypeConvertor.transformChangeEvent(genericRecord, "all_types"));
    // Verify that the mock method was called.
    Mockito.verify(mockSchemaMapper).getDialect();
  }

  @Test
  public void transformChangeEventTest_catchAllException() {
    ISchemaMapper mockSchemaMapper = mock(ISchemaMapper.class);
    when(mockSchemaMapper.getSpannerTableName(anyString(), anyString())).thenReturn("test");
    when(mockSchemaMapper.getSpannerColumns(anyString(), anyString()))
        .thenReturn(List.of("bool_col"));
    when(mockSchemaMapper.getSyntheticPrimaryKeyColName(anyString(), anyString()))
        .thenThrow(new RuntimeException());

    GenericRecordTypeConvertor genericRecordTypeConvertor =
        new GenericRecordTypeConvertor(mockSchemaMapper, "", null, null);
    assertThrows(
        RuntimeException.class,
        () -> genericRecordTypeConvertor.transformChangeEvent(null, "all_types"));
    // Verify that the mock method was called.
    Mockito.verify(mockSchemaMapper).getSyntheticPrimaryKeyColName(anyString(), anyString());
  }

  @Test
  public void transformChangeEventTest_identityMapper_noShardIdPopulation()
      throws InvalidTransformationException {
    GenericRecord genericRecord =
        new GenericData.Record(
            SchemaBuilder.record("all_types")
                .namespace("com.test.schema")
                .fields()
                .name("int_col")
                .type(unionNullType(Schema.create(Schema.Type.LONG)))
                .noDefault()
                .endRecord());
    genericRecord.put("int_col", 5);

    GenericRecordTypeConvertor genericRecordTypeConvertor =
        new GenericRecordTypeConvertor(
            new IdentityMapper(
                Ddl.builder(Dialect.GOOGLE_STANDARD_SQL)
                    .createTable("all_types")
                    .column("int_col")
                    .int64()
                    .notNull()
                    .endColumn()
                    .primaryKey()
                    .asc("int_col")
                    .end()
                    .endTable()
                    .build()),
            "",
            "xyz",
            null);
    Map<String, Value> actual =
        genericRecordTypeConvertor.transformChangeEvent(genericRecord, "all_types");
    Map<String, Value> expected = Map.of("int_col", Value.int64(5));
    // When using identity mapper, shard id should not get populated since shard id col won't exist.
    assertEquals(expected, actual);
  }

  @Test
  public void transformChangeEventTest_ShardIdPopulation() throws InvalidTransformationException {
    Ddl shardedDdl =
        Ddl.builder()
            .createTable("new_cart")
            .column("new_quantity")
            .int64()
            .notNull()
            .endColumn()
            .column("new_product_id")
            .string()
            .size(20)
            .endColumn()
            .column("new_user_id")
            .string()
            .size(20)
            .endColumn()
            .primaryKey()
            .asc("new_user_id")
            .asc("new_product_id")
            .end()
            .endTable()
            .createTable("new_people")
            .column("migration_shard_id")
            .string()
            .size(20)
            .endColumn()
            .column("new_name")
            .string()
            .size(20)
            .endColumn()
            .primaryKey()
            .asc("migration_shard_id")
            .asc("new_name")
            .end()
            .endTable()
            .build();

    String shardedSessionFilePath =
        Paths.get(Resources.getResource("session-file-sharded.json").getPath()).toString();

    ISchemaMapper shardedMapper = new SessionBasedMapper(shardedSessionFilePath, shardedDdl);
    GenericRecord genericRecord =
        new GenericData.Record(
            SchemaBuilder.record("people")
                .namespace("com.test.schema")
                .fields()
                .name("name")
                .type(unionNullType(Schema.create(Schema.Type.STRING)))
                .noDefault()
                .endRecord());
    genericRecord.put("name", "name1");

    GenericRecordTypeConvertor genericRecordTypeConvertor =
        new GenericRecordTypeConvertor(shardedMapper, "", "id1", null);
    Map<String, Value> actual =
        genericRecordTypeConvertor.transformChangeEvent(genericRecord, "people");
    Map<String, Value> expected =
        Map.of("new_name", Value.string("name1"), "migration_shard_id", Value.string("id1"));
    assertEquals(expected, actual);

    // Null shard id case, shard id population should be skipped.
    genericRecordTypeConvertor = new GenericRecordTypeConvertor(shardedMapper, "", null, null);
    actual = genericRecordTypeConvertor.transformChangeEvent(genericRecord, "people");
    // Shard id should not be present.
    assertEquals(Map.of("new_name", Value.string("name1")), actual);
  }

  @Test
  public void transformChangeEventTest_IdentityMapper_ExtraSpannerColumns()
      throws InvalidTransformationException {
    GenericRecord genericRecord =
        new GenericData.Record(
            SchemaBuilder.record("simple_table")
                .namespace("com.test.schema")
                .fields()
                .name("col1")
                .type(unionNullType(Schema.create(Schema.Type.INT)))
                .noDefault()
                .endRecord());
    genericRecord.put("col1", 123);

    // Create an identityMapper using a ddl with 1 extra column.
    Ddl ddl =
        Ddl.builder(Dialect.GOOGLE_STANDARD_SQL)
            .createTable("simple_table")
            .column("col1")
            .int64()
            .endColumn()
            .column("extra_col")
            .string()
            .size(10)
            .endColumn()
            .primaryKey()
            .asc("col1")
            .end()
            .endTable()
            .build();

    ISchemaMapper identityMapper = new IdentityMapper(ddl);

    // Transform the generic record
    GenericRecordTypeConvertor genericRecordTypeConvertor =
        new GenericRecordTypeConvertor(identityMapper, "", null, null);
    Map<String, Value> actual =
        genericRecordTypeConvertor.transformChangeEvent(genericRecord, "simple_table");

    // Assert that the extra column is not in the final result set
    assertEquals(1, actual.size());
    assertEquals(Value.int64(123), actual.get("col1"));
  }

  @Test
  public void transformChangeEventTest_SessionMapper_ExtraSpannerColumns()
      throws InvalidTransformationException {
    GenericRecord genericRecord =
        new GenericData.Record(
            SchemaBuilder.record("my_table")
                .namespace("com.test.schema")
                .fields()
                .name("id")
                .type(unionNullType(Schema.create(Schema.Type.INT)))
                .noDefault()
                .name("name")
                .type(unionNullType(Schema.create(Schema.Type.STRING)))
                .noDefault()
                .endRecord());
    genericRecord.put("id", 1);
    genericRecord.put("name", "name1");

    // Create an identityMapper using a ddl with 1 extra column.
    Ddl ddl =
        Ddl.builder(Dialect.GOOGLE_STANDARD_SQL)
            .createTable("my_table")
            .column("id")
            .int64()
            .endColumn()
            .column("name")
            .string()
            .size(10)
            .endColumn()
            .column("extra_col")
            .int64()
            .endColumn()
            .primaryKey()
            .asc("id")
            .end()
            .endTable()
            .build();

    String sessionFileWithExtraColumn =
        Paths.get(Resources.getResource("session-file-with-extra-spanner-column.json").getPath())
            .toString();
    ISchemaMapper mapper = new SessionBasedMapper(sessionFileWithExtraColumn, ddl);

    // Transform the generic record
    GenericRecordTypeConvertor genericRecordTypeConvertor =
        new GenericRecordTypeConvertor(mapper, "", null, null);
    Map<String, Value> actual =
        genericRecordTypeConvertor.transformChangeEvent(genericRecord, "my_table");

    // Assert that the extra column is not in the final result set
    assertEquals(2, actual.size());
    assertEquals(Value.int64(1), actual.get("id"));
    assertEquals(Value.string("name1"), actual.get("name"));
  }

  @Test
  public void transformChangeEventTest_SynthPKPopulation() throws InvalidTransformationException {
    String sessionFilePath =
        Paths.get(Resources.getResource("session-file-with-dropped-column.json").getPath())
            .toString();
    Ddl ddl =
        Ddl.builder()
            .createTable("new_cart")
            .column("new_quantity")
            .int64()
            .notNull()
            .endColumn()
            .column("new_user_id")
            .string()
            .size(10)
            .endColumn()
            .primaryKey()
            .asc("new_user_id")
            .asc("new_quantity")
            .end()
            .endTable()
            .createTable("new_people")
            .column("synth_id")
            .int64()
            .notNull()
            .endColumn()
            .column("new_name")
            .string()
            .size(10)
            .endColumn()
            .primaryKey()
            .asc("synth_id")
            .end()
            .endTable()
            .build();

    ISchemaMapper sessionMapper = new SessionBasedMapper(sessionFilePath, ddl);

    GenericRecord genericRecord =
        new GenericData.Record(
            SchemaBuilder.record("people")
                .namespace("com.test.schema")
                .fields()
                .name("name")
                .type(unionNullType(Schema.create(Schema.Type.STRING)))
                .noDefault()
                .endRecord());
    genericRecord.put("name", "name1");

    GenericRecordTypeConvertor genericRecordTypeConvertor =
        new GenericRecordTypeConvertor(sessionMapper, "", null, null);
    Map<String, Value> actual =
        genericRecordTypeConvertor.transformChangeEvent(genericRecord, "people");

    // Check that the synthetic primary key column exists in the result set.
    assertTrue(actual.containsKey("synth_id"));
    assertEquals(Value.string("name1"), actual.get("new_name"));
  }
}
