/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.sort.cdc.mongodb.debezium.table;

import com.google.gson.Gson;
import com.mongodb.client.model.changestream.OperationType;
import com.mongodb.internal.HexUtils;
import com.ververica.cdc.connectors.mongodb.internal.MongoDBEnvelope;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.table.data.DecimalData;
import org.apache.flink.table.data.GenericArrayData;
import org.apache.flink.table.data.GenericMapData;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.StringData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.types.logical.ArrayType;
import org.apache.flink.table.types.logical.DecimalType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.MapType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.table.types.logical.utils.LogicalTypeUtils;
import org.apache.flink.types.RowKind;
import org.apache.flink.util.Collector;
import org.apache.inlong.sort.cdc.mongodb.debezium.DebeziumDeserializationSchema;
import org.apache.inlong.sort.cdc.mongodb.debezium.utils.RecordUtils;
import org.apache.inlong.sort.cdc.mongodb.table.filter.MongoRowKind;
import org.apache.inlong.sort.cdc.mongodb.table.filter.RowKindValidator;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.bson.BsonArray;
import org.bson.BsonBinary;
import org.bson.BsonBinarySubType;
import org.bson.BsonDateTime;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonMaxKey;
import org.bson.BsonMinKey;
import org.bson.BsonRegularExpression;
import org.bson.BsonString;
import org.bson.BsonTimestamp;
import org.bson.BsonUndefined;
import org.bson.BsonValue;
import org.bson.types.Decimal128;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;

public class MongoDBConnectorDeserializationSchema
        implements
            DebeziumDeserializationSchema<RowData> {

    protected static final Logger LOG = LoggerFactory.getLogger(MongoDBConnectorDeserializationSchema.class);
    private static final long serialVersionUID = 1750787080613035184L;

    private static Gson gson = new Gson();

    /** TypeInformation of the produced {@link RowData}. */
    private final TypeInformation<RowData> resultTypeInfo;

    public static final String DOCUMENT_TO_FIELD = "to";

    /** Local Time zone. */
    private final ZoneId localTimeZone;

    /**
     * Runtime converter that converts {@link
     * com.mongodb.client.model.changestream.ChangeStreamDocument}s into {@link RowData} consisted
     * of physical column values.
     */
    protected final DeserializationRuntimeConverter physicalConverter;

    /** Whether the deserializer needs to handle metadata columns. */
    protected final boolean hasMetadata;

    /**
     * A wrapped output collector which is used to append metadata columns after physical columns.
     */
    private final AppendMetadataCollector appendMetadataCollector;

    private boolean sourceMultipleEnable = false;

    /**
     * Validator to validate the row value.
     */
    private final RowKindValidator rowKindValidator;

    public MongoDBConnectorDeserializationSchema(
            RowType physicalDataType,
            MetadataConverter[] metadataConverters,
            TypeInformation<RowData> resultTypeInfo,
            ZoneId localTimeZone,
            RowKindValidator rowValidator,
            boolean sourceMultipleEnable) {
        // this.hasMetadata = checkNotNull(metadataConverters).length > 0;
        this.hasMetadata = true;
        this.sourceMultipleEnable = sourceMultipleEnable;
        this.appendMetadataCollector = new AppendMetadataCollector(metadataConverters, sourceMultipleEnable);
        this.physicalConverter = createConverter(physicalDataType);
        this.resultTypeInfo = resultTypeInfo;
        this.localTimeZone = localTimeZone;
        this.rowKindValidator = rowValidator;
    }

    @Override
    public void deserialize(SourceRecord record, Collector<RowData> out) throws Exception {
        Struct value = (Struct) record.value();
        Schema valueSchema = record.valueSchema();

        OperationType op = operationTypeFor(record);
        BsonDocument documentKey = new BsonDocument();
        BsonDocument fullDocument = new BsonDocument();
        if (op.equals(OperationType.INSERT)
                || op.equals(OperationType.DELETE)
                || op.equals(OperationType.UPDATE)) {
            documentKey =
                    checkNotNull(
                            extractBsonDocument(
                                    value, valueSchema, MongoDBEnvelope.DOCUMENT_KEY_FIELD));
            fullDocument =
                    extractBsonDocument(value, valueSchema, MongoDBEnvelope.FULL_DOCUMENT_FIELD);
        }
        switch (op) {
            case INSERT:
                if (!rowKindValidator.validate(MongoRowKind.INSERT)) {
                    return;
                }
                GenericRowData insert = extractRowData(fullDocument);
                insert.setRowKind(RowKind.INSERT);
                emit(record, insert, out);
                break;
            case DELETE:
                if (!rowKindValidator.validate(MongoRowKind.DELETE)) {
                    return;
                }
                GenericRowData delete = extractRowData(documentKey);
                delete.setRowKind(RowKind.DELETE);
                emit(record, delete, out);
                break;
            case UPDATE:
                if (!rowKindValidator.validate(MongoRowKind.UPDATE_AFTER)) {
                    return;
                }
                // It’s null if another operation deletes the document
                // before the lookup operation happens. Ignored it.
                if (fullDocument == null) {
                    break;
                }
                GenericRowData updateAfter = extractRowData(fullDocument);
                updateAfter.setRowKind(RowKind.UPDATE_AFTER);
                emit(record, updateAfter, out);
                break;
            case REPLACE:
                if (!rowKindValidator.validate(MongoRowKind.UPDATE_BEFORE)) {
                    return;
                }
                GenericRowData replaceAfter = extractRowData(fullDocument);
                replaceAfter.setRowKind(RowKind.UPDATE_AFTER);
                emit(record, replaceAfter, out);
                break;
            case INVALIDATE:
            case DROP:
                if (!rowKindValidator.validate(MongoRowKind.DROP)) {
                    return;
                }
                GenericRowData drop = extractMongoDMLData(value,
                        MongoDBEnvelope.NAMESPACE_FIELD, OperationType.DROP.getValue());
                drop.setRowKind(RowKind.INSERT);
                emit(record, drop, out);
                break;
            case DROP_DATABASE:
                if (!rowKindValidator.validate(MongoRowKind.DROP_DATABASE)) {
                    return;
                }
                GenericRowData dropDatabase = extractMongoDMLData(value, MongoDBEnvelope.NAMESPACE_FIELD,
                        OperationType.DROP_DATABASE.getValue());
                dropDatabase.setRowKind(RowKind.INSERT);
                emit(record, dropDatabase, out);
                break;
            case RENAME:
                if (!rowKindValidator.validate(MongoRowKind.RENAME)) {
                    return;
                }
                GenericRowData rename = extractMongoDMLData(value, DOCUMENT_TO_FIELD, OperationType.RENAME.getValue());
                rename.setRowKind(RowKind.INSERT);
                emit(record, rename, out);
                break;
            case OTHER:
            default:
                break;
        }
    }

    private GenericRowData extractMongoDMLData(Struct value, String keyFiled, String ddlType) {
        Struct documentTo = (Struct) value.get(keyFiled);
        String newDb = documentTo.getString(MongoDBEnvelope.NAMESPACE_DATABASE_FIELD);
        String newColl = documentTo.getString(MongoDBEnvelope.NAMESPACE_COLLECTION_FIELD);
        String renameFormatStr = newDb + "." + newColl;
        Map<String, Object> data = new HashMap<>();
        Map<String, String> dataType = new HashMap<>();
        data.put(ddlType, renameFormatStr);
        dataType.put(ddlType, "VARCHAR");
        GenericRowData ddlRowData = new GenericRowData(2);
        ddlRowData.setField(0, data);
        ddlRowData.setField(1, dataType);
        return ddlRowData;

    }

    private GenericRowData extractRowData(BsonDocument document) throws Exception {
        checkNotNull(document);
        return (GenericRowData) physicalConverter.convert(document);
    }

    private BsonDocument extractBsonDocument(Struct value, Schema valueSchema, String fieldName) {
        if (valueSchema.field(fieldName) != null) {
            String docString = value.getString(fieldName);
            if (docString != null) {
                return BsonDocument.parse(value.getString(fieldName));
            }
        }
        return null;
    }

    @Override
    public TypeInformation<RowData> getProducedType() {
        return resultTypeInfo;
    }

    private OperationType operationTypeFor(SourceRecord record) {
        Struct value = (Struct) record.value();
        return OperationType.fromString(value.getString(MongoDBEnvelope.OPERATION_TYPE_FIELD));
    }

    private void emit(SourceRecord inRecord, RowData physicalRow, Collector<RowData> collector) {
        if (!hasMetadata) {
            collector.collect(physicalRow);
            return;
        }

        appendMetadataCollector.inputRecord = inRecord;
        appendMetadataCollector.outputCollector = collector;
        appendMetadataCollector.collect(physicalRow);
    }

    // -------------------------------------------------------------------------------------
    // Runtime Converters
    // -------------------------------------------------------------------------------------

    /**
     * Runtime converter that converts objects of MongoDB Connect into objects of Flink Table & SQL
     * internal data structures.
     */
    @FunctionalInterface
    private interface DeserializationRuntimeConverter extends Serializable {

        Object convert(BsonValue docObj) throws Exception;
    }

    /** Creates a runtime converter which is null safe. */
    private DeserializationRuntimeConverter createConverter(LogicalType type) {
        return wrapIntoNullableConverter(createNotNullConverter(type));
    }

    /** Creates a runtime converter which assuming input object is not null. */
    private DeserializationRuntimeConverter createNotNullConverter(LogicalType type) {
        switch (type.getTypeRoot()) {
            case NULL:
                return (docObj) -> null;
            case BOOLEAN:
                return this::convertToBoolean;
            case TINYINT:
                return this::convertToTinyInt;
            case SMALLINT:
                return this::convertToSmallInt;
            case INTEGER:
            case INTERVAL_YEAR_MONTH:
                return this::convertToInt;
            case BIGINT:
            case INTERVAL_DAY_TIME:
                return this::convertToLong;
            case DATE:
                return this::convertToDate;
            case TIME_WITHOUT_TIME_ZONE:
                return this::convertToTime;
            case TIMESTAMP_WITHOUT_TIME_ZONE:
                return this::convertToTimestamp;
            case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                return this::convertToLocalTimeZoneTimestamp;
            case FLOAT:
                return this::convertToFloat;
            case DOUBLE:
                return this::convertToDouble;
            case CHAR:
            case VARCHAR:
                return this::convertToString;
            case BINARY:
            case VARBINARY:
                return this::convertToBinary;
            case DECIMAL:
                return createDecimalConverter((DecimalType) type);
            case ROW:
                return createRowConverter((RowType) type);
            case ARRAY:
                return createArrayConverter((ArrayType) type);
            case MAP:
                return createMapConverter((MapType) type);
            case MULTISET:
            case RAW:
            default:
                throw new UnsupportedOperationException("Unsupported type: " + type);
        }
    }

    private boolean convertToBoolean(BsonValue docObj) {
        if (docObj.isBoolean()) {
            return docObj.asBoolean().getValue();
        }
        if (docObj.isInt32()) {
            return docObj.asInt32().getValue() == 1;
        }
        if (docObj.isInt64()) {
            return docObj.asInt64().getValue() == 1L;
        }
        if (docObj.isString()) {
            return Boolean.parseBoolean(docObj.asString().getValue());
        }
        throw new IllegalArgumentException(
                "Unable to convert to boolean from unexpected value '"
                        + docObj
                        + "' of type "
                        + docObj.getBsonType());
    }

    private byte convertToTinyInt(BsonValue docObj) {
        if (docObj.isBoolean()) {
            return docObj.asBoolean().getValue() ? (byte) 1 : (byte) 0;
        }
        if (docObj.isInt32()) {
            return (byte) docObj.asInt32().getValue();
        }
        if (docObj.isInt64()) {
            return (byte) docObj.asInt64().getValue();
        }
        if (docObj.isString()) {
            return Byte.parseByte(docObj.asString().getValue());
        }
        throw new IllegalArgumentException(
                "Unable to convert to tinyint from unexpected value '"
                        + docObj
                        + "' of type "
                        + docObj.getBsonType());
    }

    private short convertToSmallInt(BsonValue docObj) {
        if (docObj.isBoolean()) {
            return docObj.asBoolean().getValue() ? (short) 1 : (short) 0;
        }
        if (docObj.isInt32()) {
            return (short) docObj.asInt32().getValue();
        }
        if (docObj.isInt64()) {
            return (short) docObj.asInt64().getValue();
        }
        if (docObj.isString()) {
            return Short.parseShort(docObj.asString().getValue());
        }
        throw new IllegalArgumentException(
                "Unable to convert to smallint from unexpected value '"
                        + docObj
                        + "' of type "
                        + docObj.getBsonType());
    }

    private int convertToInt(BsonValue docObj) {
        if (docObj.isNumber()) {
            return docObj.asNumber().intValue();
        }
        if (docObj.isDecimal128()) {
            Decimal128 decimal128Value = docObj.asDecimal128().decimal128Value();
            if (decimal128Value.isFinite()) {
                return decimal128Value.intValue();
            } else if (decimal128Value.isNegative()) {
                return Integer.MIN_VALUE;
            } else {
                return Integer.MAX_VALUE;
            }
        }
        if (docObj.isBoolean()) {
            return docObj.asBoolean().getValue() ? 1 : 0;
        }
        if (docObj.isDateTime()) {
            return Math.toIntExact(docObj.asDateTime().getValue() / 1000L);
        }
        if (docObj.isTimestamp()) {
            return docObj.asTimestamp().getTime();
        }
        if (docObj.isString()) {
            return Integer.parseInt(docObj.asString().getValue());
        }
        throw new IllegalArgumentException(
                "Unable to convert to integer from unexpected value '"
                        + docObj
                        + "' of type "
                        + docObj.getBsonType());
    }

    private long convertToLong(BsonValue docObj) {
        if (docObj.isNumber()) {
            return docObj.asNumber().longValue();
        }
        if (docObj.isDecimal128()) {
            Decimal128 decimal128Value = docObj.asDecimal128().decimal128Value();
            if (decimal128Value.isFinite()) {
                return decimal128Value.longValue();
            } else if (decimal128Value.isNegative()) {
                return Long.MIN_VALUE;
            } else {
                return Long.MAX_VALUE;
            }
        }
        if (docObj.isBoolean()) {
            return docObj.asBoolean().getValue() ? 1L : 0L;
        }
        if (docObj.isDateTime()) {
            return docObj.asDateTime().getValue();
        }
        if (docObj.isTimestamp()) {
            return docObj.asTimestamp().getTime() * 1000L;
        }
        if (docObj.isString()) {
            return Long.parseLong(docObj.asString().getValue());
        }
        throw new IllegalArgumentException(
                "Unable to convert to long from unexpected value '"
                        + docObj
                        + "' of type "
                        + docObj.getBsonType());
    }

    private double convertToDouble(BsonValue docObj) {
        if (docObj.isNumber()) {
            return docObj.asNumber().doubleValue();
        }
        if (docObj.isDecimal128()) {
            Decimal128 decimal128Value = docObj.asDecimal128().decimal128Value();
            if (decimal128Value.isFinite()) {
                return decimal128Value.doubleValue();
            } else if (decimal128Value.isNegative()) {
                return -Double.MAX_VALUE;
            } else {
                return Double.MAX_VALUE;
            }
        }
        if (docObj.isBoolean()) {
            return docObj.asBoolean().getValue() ? 1 : 0;
        }
        if (docObj.isString()) {
            return Double.parseDouble(docObj.asString().getValue());
        }
        throw new IllegalArgumentException(
                "Unable to convert to double from unexpected value '"
                        + docObj
                        + "' of type "
                        + docObj.getBsonType());
    }

    private float convertToFloat(BsonValue docObj) {
        if (docObj.isInt32()) {
            return docObj.asInt32().getValue();
        }
        if (docObj.isInt64()) {
            return docObj.asInt64().getValue();
        }
        if (docObj.isDouble()) {
            return ((Double) docObj.asDouble().getValue()).floatValue();
        }
        if (docObj.isDecimal128()) {
            Decimal128 decimal128Value = docObj.asDecimal128().decimal128Value();
            if (decimal128Value.isFinite()) {
                return decimal128Value.floatValue();
            } else if (decimal128Value.isNegative()) {
                return -Float.MAX_VALUE;
            } else {
                return Float.MAX_VALUE;
            }
        }
        if (docObj.isBoolean()) {
            return docObj.asBoolean().getValue() ? 1f : 0f;
        }
        if (docObj.isString()) {
            return Float.parseFloat(docObj.asString().getValue());
        }
        throw new IllegalArgumentException(
                "Unable to convert to float from unexpected value '"
                        + docObj
                        + "' of type "
                        + docObj.getBsonType());
    }

    private LocalDate convertInstantToLocalDate(Instant instant) {
        return convertInstantToLocalDateTime(instant).toLocalDate();
    }

    private LocalTime convertInstantToLocalTime(Instant instant) {
        return convertInstantToLocalDateTime(instant).toLocalTime();
    }

    private LocalDateTime convertInstantToLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, localTimeZone);
    }

    private Instant convertToInstant(BsonTimestamp bsonTimestamp) {
        return Instant.ofEpochSecond(bsonTimestamp.getTime());
    }

    private Instant convertToInstant(BsonDateTime bsonDateTime) {
        return Instant.ofEpochMilli(bsonDateTime.getValue());
    }

    /**
     * A conversion from DateType to int describes the number of days since epoch.
     *
     * @see org.apache.flink.table.types.logical.DateType
     */
    private int convertToDate(BsonValue docObj) {
        if (docObj.isDateTime()) {
            Instant instant = convertToInstant(docObj.asDateTime());
            return (int) convertInstantToLocalDate(instant).toEpochDay();
        }
        if (docObj.isTimestamp()) {
            Instant instant = convertToInstant(docObj.asTimestamp());
            return (int) convertInstantToLocalDate(instant).toEpochDay();
        }
        throw new IllegalArgumentException(
                "Unable to convert to date from unexpected value '"
                        + docObj
                        + "' of type "
                        + docObj.getBsonType());
    }

    /**
     * A conversion from TimeType to int describes the number of milliseconds of the day.
     *
     * @see org.apache.flink.table.types.logical.TimeType
     */
    private int convertToTime(BsonValue docObj) {
        if (docObj.isDateTime()) {
            Instant instant = convertToInstant(docObj.asDateTime());
            return convertInstantToLocalTime(instant).toSecondOfDay() * 1000;
        }
        if (docObj.isTimestamp()) {
            Instant instant = convertToInstant(docObj.asTimestamp());
            return convertInstantToLocalTime(instant).toSecondOfDay() * 1000;
        }
        throw new IllegalArgumentException(
                "Unable to convert to time from unexpected value '"
                        + docObj
                        + "' of type "
                        + docObj.getBsonType());
    }

    private TimestampData convertToTimestamp(BsonValue docObj) {
        if (docObj.isDateTime()) {
            return TimestampData.fromLocalDateTime(
                    convertInstantToLocalDateTime(convertToInstant(docObj.asDateTime())));
        }
        if (docObj.isTimestamp()) {
            return TimestampData.fromLocalDateTime(
                    convertInstantToLocalDateTime(convertToInstant(docObj.asTimestamp())));
        }

        throw new IllegalArgumentException(
                "Unable to convert to timestamp from unexpected value '"
                        + docObj
                        + "' of type "
                        + docObj.getBsonType());
    }

    private TimestampData convertToLocalTimeZoneTimestamp(BsonValue docObj) {
        if (docObj.isDateTime()) {
            return TimestampData.fromEpochMillis(docObj.asDateTime().getValue());
        }
        if (docObj.isTimestamp()) {
            return TimestampData.fromEpochMillis(docObj.asTimestamp().getTime() * 1000L);
        }
        throw new IllegalArgumentException(
                "Unable to convert to timestamp with local timezone from unexpected value '"
                        + docObj
                        + "' of type "
                        + docObj.getBsonType());
    }

    private byte[] convertToBinary(BsonValue docObj) {
        if (docObj.isBinary()) {
            return docObj.asBinary().getData();
        }
        throw new UnsupportedOperationException(
                "Unsupported BYTES value type: " + docObj.getClass().getSimpleName());
    }

    private StringData convertToString(BsonValue docObj) {
        if (docObj.isString()) {
            return StringData.fromString(docObj.asString().getValue());
        }
        if (docObj.isBinary()) {
            BsonBinary bsonBinary = docObj.asBinary();
            if (BsonBinarySubType.isUuid(bsonBinary.getType())) {
                return StringData.fromString(bsonBinary.asUuid().toString());
            }
            return StringData.fromString(HexUtils.toHex(bsonBinary.getData()));
        }
        if (docObj.isObjectId()) {
            return StringData.fromString(docObj.asObjectId().getValue().toHexString());
        }
        if (docObj.isInt32()) {
            return StringData.fromString(String.valueOf(docObj.asInt32().getValue()));
        }
        if (docObj.isInt64()) {
            return StringData.fromString(String.valueOf(docObj.asInt64().getValue()));
        }
        if (docObj.isDouble()) {
            return StringData.fromString(String.valueOf(docObj.asDouble().getValue()));
        }
        if (docObj.isDecimal128()) {
            return StringData.fromString(docObj.asDecimal128().getValue().toString());
        }
        if (docObj.isBoolean()) {
            return StringData.fromString(String.valueOf(docObj.asBoolean().getValue()));
        }
        if (docObj.isDateTime()) {
            Instant instant = convertToInstant(docObj.asDateTime());
            return StringData.fromString(
                    convertInstantToLocalDateTime(instant).format(ISO_OFFSET_DATE_TIME));
        }
        if (docObj.isTimestamp()) {
            Instant instant = convertToInstant(docObj.asTimestamp());
            return StringData.fromString(
                    convertInstantToLocalDateTime(instant).format(ISO_OFFSET_DATE_TIME));
        }
        if (docObj.isRegularExpression()) {
            BsonRegularExpression regex = docObj.asRegularExpression();
            return StringData.fromString(
                    String.format("/%s/%s", regex.getPattern(), regex.getOptions()));
        }
        if (docObj.isJavaScript()) {
            return StringData.fromString(docObj.asJavaScript().getCode());
        }
        if (docObj.isJavaScriptWithScope()) {
            return StringData.fromString(docObj.asJavaScriptWithScope().getCode());
        }
        if (docObj.isSymbol()) {
            return StringData.fromString(docObj.asSymbol().getSymbol());
        }
        if (docObj.isDBPointer()) {
            return StringData.fromString(docObj.asDBPointer().getId().toHexString());
        }
        if (docObj instanceof BsonMinKey || docObj instanceof BsonMaxKey) {
            return StringData.fromString(docObj.getBsonType().name());
        }
        // TODO unconfirmed feature
        if (docObj instanceof BsonArray) {
            String valueStr = "{";
            for (BsonValue bsonValue : ((BsonArray) docObj).getValues()) {
                switch (bsonValue.getBsonType()) {
                    case DOUBLE:
                        valueStr = valueStr + (int) ((BsonDouble) bsonValue).getValue() + ",";
                        break;
                    case INT32:
                        valueStr = valueStr + ((BsonInt32) bsonValue).getValue() + ",";
                        break;
                    case INT64:
                        valueStr = valueStr + ((BsonInt64) bsonValue).getValue() + ",";
                        break;
                    case STRING:
                        valueStr = valueStr + ((BsonString) bsonValue).getValue() + ",";
                        break;
                    default:
                        valueStr = valueStr + bsonValue + ",";
                }
            }
            valueStr = valueStr.substring(0, valueStr.length() - 1) + "}";
            return StringData.fromString(valueStr);
        }
        if (docObj.isDocument()) {
            return StringData.fromString(gson.toJson(docObj));
        }

        throw new IllegalArgumentException(
                "Unable to convert to string from unexpected value '"
                        + docObj
                        + "' of type "
                        + docObj.getBsonType());
    }

    private DeserializationRuntimeConverter createDecimalConverter(DecimalType decimalType) {
        final int precision = decimalType.getPrecision();
        final int scale = decimalType.getScale();
        return (docObj) -> {
            BigDecimal bigDecimal;
            if (docObj.isString()) {
                bigDecimal = new BigDecimal(docObj.asString().getValue());
            } else if (docObj.isDecimal128()) {
                Decimal128 decimal128Value = docObj.asDecimal128().decimal128Value();
                if (decimal128Value.isFinite()) {
                    bigDecimal = docObj.asDecimal128().decimal128Value().bigDecimalValue();
                } else if (decimal128Value.isNegative()) {
                    bigDecimal = BigDecimal.valueOf(-Double.MAX_VALUE);
                } else {
                    bigDecimal = BigDecimal.valueOf(Double.MAX_VALUE);
                }
            } else if (docObj.isDouble()) {
                bigDecimal = BigDecimal.valueOf(docObj.asDouble().doubleValue());
            } else if (docObj.isInt32()) {
                bigDecimal = BigDecimal.valueOf(docObj.asInt32().getValue());
            } else if (docObj.isInt64()) {
                bigDecimal = BigDecimal.valueOf(docObj.asInt64().getValue());
            } else {
                throw new IllegalArgumentException(
                        "Unable to convert to decimal from unexpected value '"
                                + docObj
                                + "' of type "
                                + docObj.getBsonType());
            }
            return DecimalData.fromBigDecimal(bigDecimal, precision, scale);
        };
    }

    private DeserializationRuntimeConverter createRowConverter(RowType rowType) {
        final DeserializationRuntimeConverter[] fieldConverters =
                rowType.getFields().stream()
                        .map(RowType.RowField::getType)
                        .map(this::createConverter)
                        .toArray(DeserializationRuntimeConverter[]::new);
        final String[] fieldNames = rowType.getFieldNames().toArray(new String[0]);

        return (docObj) -> {
            if (!docObj.isDocument()) {
                throw new IllegalArgumentException(
                        "Unable to convert to rowType from unexpected value '"
                                + docObj
                                + "' of type "
                                + docObj.getBsonType());
            }
            BsonDocument document = docObj.asDocument();

            if (!sourceMultipleEnable) {
                int arity = fieldNames.length;
                GenericRowData row = new GenericRowData(arity);
                for (int i = 0; i < arity; i++) {
                    String fieldName = fieldNames[i];
                    BsonValue fieldValue = document.get(fieldName);
                    Object convertedField = convertField(fieldConverters[i], fieldValue);
                    row.setField(i, convertedField);
                }
                return row;
            } else {
                Map<String, Object> data = new HashMap<>();
                Map<String, String> dataType = new HashMap<>();
                document.forEach((key, value) -> {
                    try {
                        LogicalType logicalType = RecordUtils.convertLogicType(value);
                        dataType.put(key, logicalType.getTypeRoot().name());
                        Object fieldValue = convertField(createConverter(logicalType), value);
                        if (fieldValue instanceof DecimalData) {
                            fieldValue = fieldValue.toString();
                        }
                        if (fieldValue instanceof TimestampData) {
                            fieldValue = ((TimestampData) fieldValue).toTimestamp();
                            dataType.put(key, "TIMESTAMP");
                        }
                        if (fieldValue instanceof StringData) {
                            fieldValue = fieldValue.toString();
                        }
                        data.put(key, fieldValue);
                    } catch (Exception e) {
                        LOG.error("parse value get err:", e);
                    }
                });
                GenericRowData row = new GenericRowData(2);
                row.setField(0, data);
                row.setField(1, dataType);
                return row;
            }
        };
    }

    private DeserializationRuntimeConverter createArrayConverter(ArrayType arrayType) {
        final Class<?> elementClass =
                LogicalTypeUtils.toInternalConversionClass(arrayType.getElementType());
        final DeserializationRuntimeConverter elementConverter =
                createConverter(arrayType.getElementType());

        return (docObj) -> {
            if (!docObj.isArray()) {
                throw new IllegalArgumentException(
                        "Unable to convert to arrayType from unexpected value '"
                                + docObj
                                + "' of type "
                                + docObj.getBsonType());
            }

            List<BsonValue> in = docObj.asArray();
            final Object[] elementArray = (Object[]) Array.newInstance(elementClass, in.size());
            for (int i = 0; i < in.size(); i++) {
                elementArray[i] = elementConverter.convert(in.get(i));
            }
            return new GenericArrayData(elementArray);
        };
    }

    private DeserializationRuntimeConverter createMapConverter(MapType mapType) {
        LogicalType keyType = mapType.getKeyType();
        checkArgument(keyType.supportsInputConversion(String.class));

        LogicalType valueType = mapType.getValueType();
        DeserializationRuntimeConverter valueConverter = createConverter(valueType);

        return (docObj) -> {
            if (!docObj.isDocument()) {
                throw new IllegalArgumentException(
                        "Unable to convert to rowType from unexpected value '"
                                + docObj
                                + "' of type "
                                + docObj.getBsonType());
            }

            BsonDocument document = docObj.asDocument();
            Map<StringData, Object> map = new HashMap<>();
            for (String key : document.keySet()) {
                map.put(
                        StringData.fromString(key),
                        convertField(valueConverter, document.get(key)));
            }
            return new GenericMapData(map);
        };
    }

    private Object convertField(
            DeserializationRuntimeConverter fieldConverter, BsonValue fieldValue) throws Exception {
        if (fieldValue == null) {
            return null;
        } else {
            return fieldConverter.convert(fieldValue);
        }
    }

    private DeserializationRuntimeConverter wrapIntoNullableConverter(
            DeserializationRuntimeConverter converter) {
        return (docObj) -> {
            if (docObj == null || docObj.isNull() || docObj instanceof BsonUndefined) {
                return null;
            }
            if (docObj.isDecimal128() && docObj.asDecimal128().getValue().isNaN()) {
                return null;
            }
            return converter.convert(docObj);
        };
    }
}
