package com.orientechnologies.orient.core.serialization.serializer.record.binary;

import com.orientechnologies.common.serialization.types.OByteSerializer;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.orient.core.db.ODatabaseDocumentInternal;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.exception.OSerializationException;
import com.orientechnologies.orient.core.metadata.OMetadataInternal;
import com.orientechnologies.orient.core.metadata.schema.*;
import com.orientechnologies.orient.core.record.ORecordInternal;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.record.impl.ODocumentEntry;
import com.orientechnologies.orient.core.record.impl.ODocumentInternal;
import com.orientechnologies.orient.core.serialization.serializer.record.binary.HelperClasses.Triple;
import com.orientechnologies.orient.core.serialization.serializer.record.binary.HelperClasses.Tuple;
import java.util.*;
import java.util.Map.Entry;
import static com.orientechnologies.orient.core.serialization.serializer.record.binary.HelperClasses.*;
import java.util.stream.Collectors;

public class ORecordSerializerBinaryV1 extends ORecordSerializerBinaryV0{          
  
  private enum Signal{
    CONTINUE,
    RETURN,
    RETURN_VALUE,
    NO_ACTION
  }
  
  private Tuple<Boolean, String> processLenLargerThanZeroDeserializePartial(final String[] iFields, final BytesContainer bytes, int len, byte[][] fields){
    boolean match = false;
    String fieldName = null;
    for (int i = 0; i < iFields.length; ++i) {
      if (iFields[i] != null && iFields[i].length() == len) {
        boolean matchField = true;
        for (int j = 0; j < len; ++j) {
          if (bytes.bytes[bytes.offset + j] != fields[i][j]) {
            matchField = false;
            break;
          }
        }
        if (matchField) {
          fieldName = iFields[i];          
          match = true;
          break;
        }
      }
    }
    
    fieldName = stringFromBytes(bytes.bytes, bytes.offset, len).intern();
    bytes.skip(len);
    return new Tuple<>(match, fieldName);
  }
  
  private Tuple<Boolean, String> processLenSmallerThanZeroDeserializePartial(OGlobalProperty prop, final String[] iFields){    
    String fieldName = prop.getName();

    boolean matchField = false;
    for (String f : iFields) {
      if (fieldName.equals(f)) {
        matchField = true;
        break;
      }
    }
    
    return new Tuple<>(matchField, fieldName);
  }
  
  private Triple<Signal, Triple<Integer, OType, String>, Integer> processLessThanZeroDeserializePartialFields(final ODocument document,
          final int len, final String[] iFields, final BytesContainer bytes, int cumulativeLength, int headerStart, int headerLength){
    // LOAD GLOBAL PROPERTY BY ID
    final OGlobalProperty prop = getGlobalProperty(document, len);    
    Tuple<Boolean, String> matchFieldName = processLenSmallerThanZeroDeserializePartial(prop, iFields);

    boolean matchField = matchFieldName.getFirstVal();
    String fieldName = matchFieldName.getSecondVal();

    Integer fieldLength = OVarIntSerializer.readAsInteger(bytes);    
    OType type= getTypeForLenLessThanZero(prop, bytes);    
    
    if (!matchField) {            
      return new Triple<>(Signal.CONTINUE, null, cumulativeLength + fieldLength);
    }
    
    int valuePos;
    if (fieldLength == 0){
      valuePos = 0;
    }
    else{
      valuePos = cumulativeLength + headerStart + headerLength;
    }
    Triple<Integer, OType, String> value = new Triple<>(valuePos, type, fieldName);
    return new Triple<>(Signal.RETURN_VALUE, value, cumulativeLength + fieldLength);
  }
  
  @Override
   public void deserializePartial(ODocument document, BytesContainer bytes, String[] iFields){
    // TRANSFORMS FIELDS FOM STRINGS TO BYTE[]            
    final byte[][] fields = new byte[iFields.length][];
    for (int i = 0; i < iFields.length; ++i){      
      fields[i] = iFields[i].getBytes();
    }

    String fieldName;
    int valuePos;
    OType type;
    int unmarshalledFields = 0;
    
    int headerLength = OIntegerSerializer.INSTANCE.deserialize(bytes.bytes, bytes.offset);
    bytes.skip(OIntegerSerializer.INT_SIZE);
    int headerStart = bytes.offset;
    int cumulativeLength = 0;
    
    while (true) {
      if (bytes.offset >= headerStart + headerLength)
        break;
      
      final int len = OVarIntSerializer.readAsInteger(bytes);

      if (len > 0) {
        // CHECK BY FIELD NAME SIZE: THIS AVOID EVEN THE UNMARSHALLING OF FIELD NAME
        Tuple<Boolean, String> matchFieldName = processLenLargerThanZeroDeserializePartial(iFields, bytes, len, fields);
        boolean match = matchFieldName.getFirstVal();
        fieldName = matchFieldName.getSecondVal();
        Tuple<Integer, OType> pointerAndType = getPointerAndTypeFromCurrentPosition(bytes);
        int fieldLength = pointerAndType.getFirstVal();
        
        if (!match) {
          // FIELD NOT INCLUDED: SKIP IT
          cumulativeLength += fieldLength;
          continue;
        }
        
        type = pointerAndType.getSecondVal();
        if (fieldLength == 0){
          valuePos = 0;
        }
        else{
          valuePos = headerStart + headerLength + cumulativeLength;
        }
        cumulativeLength += fieldLength;
      } else {
        // LOAD GLOBAL PROPERTY BY ID
        Triple<Signal, Triple<Integer, OType, String>, Integer> actionSignal = processLessThanZeroDeserializePartialFields(document, len, iFields, bytes,
                cumulativeLength, headerStart, headerLength);
        cumulativeLength = actionSignal.getThirdVal();
        switch (actionSignal.getFirstVal()){
          case CONTINUE:            
            continue;            
          case RETURN_VALUE:
          default:
            valuePos = actionSignal.getSecondVal().getFirstVal();
            type = actionSignal.getSecondVal().getSecondVal();
            fieldName = actionSignal.getSecondVal().getThirdVal();            
            break;
        }        
      }

      if (valuePos != 0) {
        int headerCursor = bytes.offset;
        bytes.offset = valuePos;
        final Object value = deserializeValue(bytes, type, document);
        bytes.offset = headerCursor;
        ODocumentInternal.rawField(document, fieldName, value, type);
      } else
        ODocumentInternal.rawField(document, fieldName, null, null);

      if (++unmarshalledFields == iFields.length)
        // ALL REQUESTED FIELDS UNMARSHALLED: EXIT
        break;
    }
  }
    
  
  @Override
  public void deserializePartialWithClassName(final ODocument document, final BytesContainer bytes, final String[] iFields) {
        
    final String className = readString(bytes);
    if (className.length() != 0)
      ODocumentInternal.fillClassNameIfNeeded(document, className);    

    deserializePartial(document, bytes, iFields);
  }

  private boolean checkMatchForLargerThenZero(final BytesContainer bytes, final byte[] field, int len){
   if (field.length != len){
     return false;
   }
    boolean match = true;
    for (int j = 0; j < len; ++j)
      if (bytes.bytes[bytes.offset + j] != field[j]) {
        match = false;
        break;
      }
    
    return match;
  }
  
  private OType getTypeForLenLessThanZero(final OGlobalProperty prop, final BytesContainer bytes){
    final OType type;
    if (prop.getType() != OType.ANY)
      type = prop.getType();
    else
      type = readOType(bytes);
    
    return type;
  }
  
  private Triple<Signal, OBinaryField, Integer> processLenLargerThanZeroDeserializeField(final BytesContainer bytes, final String iFieldName,
          final byte[] field, int len, Integer cumulativeLength, int headerStart, int headerLength){
    
    boolean match = checkMatchForLargerThenZero(bytes, field, len);

    bytes.skip(len);
    Tuple<Integer, OType> pointerAndType = getPointerAndTypeFromCurrentPosition(bytes);
    final int fieldLength = pointerAndType.getFirstVal();
    final OType type = pointerAndType.getSecondVal();

    if (fieldLength == 0)
      return new Triple<>(Signal.RETURN_VALUE, null, cumulativeLength);

    if (!match)
      return new Triple<>(Signal.CONTINUE, null, cumulativeLength + fieldLength);

    if (!getComparator().isBinaryComparable(type))
      return new Triple<>(Signal.RETURN_VALUE, null, cumulativeLength + fieldLength);

    int valuePos = headerStart + headerLength + cumulativeLength;
    bytes.offset = valuePos;
    return new Triple<>(Signal.RETURN_VALUE, new OBinaryField(iFieldName, type, bytes, null), cumulativeLength + fieldLength);                    
  }
  
  private Triple<Signal, OBinaryField, Integer> processLenLessThanZeroDeserializeField(int len, final OImmutableSchema _schema,
          final String iFieldName, final OClass iClass, final BytesContainer bytes,
          int cumulativeLength, int headerStart, int headerLength){
    final int id = (len * -1) - 1;
    final OGlobalProperty prop = _schema.getGlobalPropertyById(id);    
    final int fieldLength = OVarIntSerializer.readAsInteger(bytes);
    final OType type;    
    type = getTypeForLenLessThanZero(prop, bytes);    

    if (!iFieldName.equals(prop.getName())){
      return new Triple<>(Signal.NO_ACTION, null, cumulativeLength + fieldLength);
    }

    int valuePos = headerStart + headerLength + cumulativeLength;

    if (fieldLength == 0 || valuePos == 0 || !getComparator().isBinaryComparable(type))
      return new Triple<>(Signal.RETURN_VALUE, null, cumulativeLength + fieldLength);

    bytes.offset = valuePos;

    final OProperty classProp = iClass.getProperty(iFieldName);
    return new Triple<>(Signal.RETURN_VALUE, 
            new OBinaryField(iFieldName, type, bytes, classProp != null ? classProp.getCollate() : null),
            cumulativeLength + fieldLength);            
  }
  
  @Override
  public OBinaryField deserializeField(final BytesContainer bytes, final OClass iClass, final String iFieldName){
    final byte[] field = iFieldName.getBytes();

    final OMetadataInternal metadata = (OMetadataInternal) ODatabaseRecordThreadLocal.instance().get().getMetadata();
    final OImmutableSchema _schema = metadata.getImmutableSchemaSnapshot();

    int headerLength = OIntegerSerializer.INSTANCE.deserialize(bytes.bytes, bytes.offset);
    bytes.skip(OIntegerSerializer.INT_SIZE);
    int headerStart = bytes.offset;
    int cumulativeLength = 0;
    
    while (true) {
      if (bytes.offset >= headerStart + headerLength)
        return null;
      
      final int len = OVarIntSerializer.readAsInteger(bytes);

      if (len > 0) {
        // CHECK BY FIELD NAME SIZE: THIS AVOID EVEN THE UNMARSHALLING OF FIELD NAME
        Triple<Signal, OBinaryField, Integer> actionSignal = processLenLargerThanZeroDeserializeField(bytes, iFieldName, 
                field, len, cumulativeLength, headerStart, headerLength);
        cumulativeLength = actionSignal.getThirdVal();
        switch(actionSignal.getFirstVal()){
          case RETURN_VALUE:
            return actionSignal.getSecondVal();
          case CONTINUE:            
          case NO_ACTION:
          default:
            break;
        }
      } else {
        // LOAD GLOBAL PROPERTY BY ID
        Triple<Signal, OBinaryField, Integer> actionSignal = processLenLessThanZeroDeserializeField(len, _schema, iFieldName, iClass, bytes,
                cumulativeLength, headerStart, headerLength);
        cumulativeLength = actionSignal.getThirdVal();
        switch(actionSignal.getFirstVal()){
          case RETURN_VALUE:
            return actionSignal.getSecondVal();
          case CONTINUE:            
          case NO_ACTION:
          default:
            break;
        }
      }
    }
  }
  
  @Override
  public OBinaryField deserializeFieldWithClassName(final BytesContainer bytes, final OClass iClass, final String iFieldName) {
    // SKIP CLASS NAME    
    final int classNameLen = OVarIntSerializer.readAsInteger(bytes);
    bytes.skip(classNameLen);    

    return deserializeField(bytes, iClass, iFieldName);
  }

  @Override
  public void deserialize(final ODocument document, final BytesContainer bytes) {        
    int headerLength = OIntegerSerializer.INSTANCE.deserialize(bytes.bytes, bytes.offset);
    bytes.skip(OIntegerSerializer.INT_SIZE);
    int headerStart = bytes.offset;
    
    int last = 0;
    String fieldName;    
    OType type;
    int cumulativeSize = 0;
    while (true) {
      
      if (bytes.offset >= headerStart + headerLength)
        break;
      
      OGlobalProperty prop;
      final int len = OVarIntSerializer.readAsInteger(bytes);
      int fieldLength;
      if (len > 0) {
        // PARSE FIELD NAME
        fieldName = stringFromBytes(bytes.bytes, bytes.offset, len).intern();
        bytes.skip(len);
        Tuple<Integer, OType> pointerAndType = getPointerAndTypeFromCurrentPosition(bytes);
        fieldLength = pointerAndType.getFirstVal();
        type = pointerAndType.getSecondVal();
      } else {
        // LOAD GLOBAL PROPERTY BY ID
        prop = getGlobalProperty(document, len);
        fieldName = prop.getName();
        fieldLength = OVarIntSerializer.readAsInteger(bytes);        
        type = getTypeForLenLessThanZero(prop, bytes);        
      }

      if (ODocumentInternal.rawContainsField(document, fieldName)) {
        cumulativeSize += fieldLength;
        continue;
      }

      if (fieldLength != 0) {
        int headerCursor = bytes.offset;
        
        int valuePos = cumulativeSize + headerStart + headerLength;
        
        bytes.offset = valuePos;
        final Object value = deserializeValue(bytes, type, document);
        if (bytes.offset > last) 
          last = bytes.offset;        
        bytes.offset = headerCursor;
        ODocumentInternal.rawField(document, fieldName, value, type);
      } 
      else 
        ODocumentInternal.rawField(document, fieldName, null, null);
      
      cumulativeSize += fieldLength;
    }

    ORecordInternal.clearSource(document);

    if (last > bytes.offset) {
      bytes.offset = last;
    }
  }

  @Override
  public void deserializeWithClassName(final ODocument document, final BytesContainer bytes) {

    final String className = readString(bytes);
    if (className.length() != 0)
      ODocumentInternal.fillClassNameIfNeeded(document, className);

    deserialize(document, bytes);
  }

  @Override
  public String[] getFieldNames(ODocument reference, final BytesContainer bytes,  boolean readClassName) {
   // SKIP CLASS NAME
   if (readClassName){
     final int classNameLen = OVarIntSerializer.readAsInteger(bytes);
     bytes.skip(classNameLen);
   }

   //skip header length
   int headerLength = OIntegerSerializer.INSTANCE.deserialize(bytes.bytes, bytes.offset);
   bytes.skip(OIntegerSerializer.INT_SIZE);
   int headerStart = bytes.offset;
   
   final List<String> result = new ArrayList<>();

   String fieldName;
   while (true) {
     if (bytes.offset >= headerStart + headerLength)
       break;
     
     OGlobalProperty prop = null;
     final int len = OVarIntSerializer.readAsInteger(bytes);
     if (len > 0) {
       // PARSE FIELD NAME
       fieldName = stringFromBytes(bytes.bytes, bytes.offset, len).intern();
       result.add(fieldName);

       // SKIP THE REST
       bytes.skip(len);
       OVarIntSerializer.readAsInteger(bytes);
       bytes.skip(1);
     } else {
       // LOAD GLOBAL PROPERTY BY ID
       final int id = (len * -1) - 1;
       prop = ODocumentInternal.getGlobalPropertyById(reference, id);
       if (prop == null) {
         throw new OSerializationException("Missing property definition for property id '" + id + "'");
       }
       result.add(prop.getName());

       // SKIP THE REST
       OVarIntSerializer.readAsInteger(bytes);
       if (prop.getType() == OType.ANY)
        bytes.skip(1);
     }
   }

   return result.toArray(new String[result.size()]);
  }  
  
  private static void updatePointers(BytesContainer buffer, List<Integer> pointers, int offset){    
    for (Integer pointer : pointers){
      int valuePos = OIntegerSerializer.INSTANCE.deserialize(buffer.bytes, pointer);
      valuePos += offset;
      OIntegerSerializer.INSTANCE.serialize(valuePos, buffer.bytes, pointer);
    }    
  }
  
  private List<Integer> serializeWriteValues(final BytesContainer headerBuffer, final BytesContainer valuesBuffer, final ODocument document,
                                    Set<Entry<String, ODocumentEntry>> fields, final Map<String, OProperty> props){
    
    List<Integer> pointersToUpdate = new ArrayList<>();
    for (Entry<String, ODocumentEntry> field : fields) {
      ODocumentEntry docEntry = field.getValue();
      if (!field.getValue().exist()) {
        continue;
      }
      if (docEntry.property == null && props != null) {
        OProperty prop = props.get(field.getKey());
        if (prop != null && docEntry.type == prop.getType()) {
          docEntry.property = prop;
        }
      }
      
      if (docEntry.property == null){
        String fieldName = field.getKey();
        writeString(headerBuffer, fieldName);
      }
      else{
        OVarIntSerializer.write(headerBuffer, (docEntry.property.getId() + 1) * -1);
      }
      
      final Object value = field.getValue().value;
      
      final OType type;
      if (value != null) {        
        type = getFieldType(field.getValue());
        if (type == null) {
          throw new OSerializationException(
              "Impossible serialize value of type " + value.getClass() + " with the ODocument binary serializer");
        }
        Triple<Integer, Integer, List<Integer>> dataPointerAndLength = serializeValue(valuesBuffer, value, type, getLinkedType(document, type, field.getKey()));
        int valueLength = dataPointerAndLength.getSecondVal();
        if (type.isEmbedded()){
          List<Integer> pointers = (List<Integer>)dataPointerAndLength.getThirdVal();
          pointersToUpdate.addAll(pointers);
        }
        OVarIntSerializer.write(headerBuffer, valueLength);                        
      }
      //handle null fields
      else{
        OVarIntSerializer.write(headerBuffer, 0);
        type = OType.ANY;
      }
      
      //write type. Type should be written both for regular and null fields
      if (field.getValue().property == null || field.getValue().property.getType() == OType.ANY){
        int typeOffset = headerBuffer.alloc(OByteSerializer.BYTE_SIZE);
        OByteSerializer.INSTANCE.serialize((byte)type.getId(), headerBuffer.bytes, typeOffset);
      }
    }
    //signal for header end maybe this is not necessary because there is header length
//    writeEmptyString(headerBuffer);
        
    return pointersToUpdate;
  }
  
  private void merge(BytesContainer destinationBuffer, BytesContainer sourceBuffer){    
    destinationBuffer.offset = destinationBuffer.allocExact(sourceBuffer.offset);
    System.arraycopy(sourceBuffer.bytes, 0, destinationBuffer.bytes, destinationBuffer.offset, sourceBuffer.offset);
    destinationBuffer.offset += sourceBuffer.offset;
  }
  
  private List<Integer> updatePointersToPointers(List<Integer> pointers, int offset) {
    return pointers.stream().map((Integer t) -> t + offset).collect(Collectors.toList());
  }
  
  private List<Integer> serializeDocument(final ODocument document, final BytesContainer bytes, final OClass clazz){         
    //allocate space for header length
    int headerLengthOffset = bytes.alloc(OIntegerSerializer.INT_SIZE);
    int headerOffset = bytes.offset;        
    
    final Map<String, OProperty> props = clazz != null ? clazz.propertiesMap() : null;
    final Set<Entry<String, ODocumentEntry>> fields = ODocumentInternal.rawEntries(document);    

    BytesContainer valuesBuffer = new BytesContainer();
    
    List<Integer> pointers = serializeWriteValues(bytes, valuesBuffer, document, fields, props);
    int headerLength = bytes.offset - headerOffset;
    //write header length as soon as possible
    OIntegerSerializer.INSTANCE.serialize(headerLength, bytes.bytes, headerLengthOffset);    
    
    updatePointers(valuesBuffer, pointers, bytes.offset);
    pointers = updatePointersToPointers(pointers, bytes.offset);    
    merge(bytes, valuesBuffer);
    return pointers;
  }
  
  @Override
  public List<Integer> serializeWithClassName(final ODocument document, final BytesContainer bytes, final boolean iClassOnly){
    final OClass clazz = serializeClass(document, bytes, true);
    if (iClassOnly) {
      writeEmptyString(bytes);
      return new ArrayList<>();
    }
    return serializeDocument(document, bytes, clazz);
  }
  
  @SuppressWarnings("unchecked")
  @Override
  public List<Integer> serialize(final ODocument document, final BytesContainer bytes, final boolean iClassOnly) {
    final OClass clazz = serializeClass(document, bytes, false);
    if (iClassOnly) {
      writeEmptyString(bytes);
      return new ArrayList<>();
    }
    return serializeDocument(document, bytes, clazz);
  }  

  protected OClass serializeClass(final ODocument document, final BytesContainer bytes, boolean serializeClassName) {
    final OClass clazz = ODocumentInternal.getImmutableSchemaClass(document);
    if (serializeClassName){
      if (clazz != null && document.isEmbedded())
        writeString(bytes, clazz.getName());
      else
        writeEmptyString(bytes);
    }
    return clazz;
  }
  
  @Override
  public boolean isSerializingClassNameByDefault() {
    return false;
  }
  
  @Override
  public <RET> RET deserializeFieldTyped(BytesContainer bytes, String iFieldName, boolean isEmbedded, int serializerVersion){    
    if (isEmbedded){
      skipClassName(bytes);
    }
    return deserializeFieldTypedLoopAndReturn(bytes, iFieldName, serializerVersion);
  }
  
  @Override
  protected <RET> RET deserializeFieldTypedLoopAndReturn(BytesContainer bytes, String iFieldName, int serializerVersion){
    final byte[] field = iFieldName.getBytes();

    int headerLength = OIntegerSerializer.INSTANCE.deserialize(bytes.bytes, bytes.offset);
    bytes.skip(OIntegerSerializer.INT_SIZE);
    int headerStart = bytes.offset;
    int cumulativeLength = 0;
    
    final OMetadataInternal metadata = (OMetadataInternal) ODatabaseRecordThreadLocal.instance().get().getMetadata();
    final OImmutableSchema _schema = metadata.getImmutableSchemaSnapshot();

    while (true) {
      if (bytes.offset >= headerStart + headerLength)
        return null;
      
      int len = OVarIntSerializer.readAsInteger(bytes);

      if (len > 0) {
        // CHECK BY FIELD NAME SIZE: THIS AVOID EVEN THE UNMARSHALLING OF FIELD NAME        
        boolean match = checkMatchForLargerThenZero(bytes, field, len);

        bytes.skip(len);
        Tuple<Integer, OType> pointerAndType = getPointerAndTypeFromCurrentPosition(bytes);
        int fieldLength = pointerAndType.getFirstVal();
        OType type = pointerAndType.getSecondVal();

        int valuePos = cumulativeLength + headerStart + headerLength;
        cumulativeLength += fieldLength;
        if (valuePos == 0 || fieldLength == 0)
          return null;

        if (!match)
          continue;

        //find start of the next field offset so current field byte length can be calculated
        //actual field byte length is only needed for embedded fields
        int fieldDataLength = -1;
        if (type.isEmbedded()){            
          fieldDataLength = getEmbeddedFieldSize(bytes, valuePos, serializerVersion, type);                        
        }                    

        bytes.offset = valuePos;
        Object value = deserializeValue(bytes, type, null, false, fieldDataLength, serializerVersion, false);
        return (RET)value;        
      } else {
        // LOAD GLOBAL PROPERTY BY ID
        final int id = (len * -1) - 1;
        final OGlobalProperty prop = _schema.getGlobalPropertyById(id);        
        final int fieldLength = OVarIntSerializer.readAsInteger(bytes);
        OType type = getTypeForLenLessThanZero(prop, bytes);        

        int valuePos = cumulativeLength + headerStart + headerLength;
        cumulativeLength += fieldLength;

        if (!iFieldName.equals(prop.getName()))
          continue;

        int fieldDataLength = -1;
        if (type.isEmbedded()){
          fieldDataLength = getEmbeddedFieldSize(bytes, valuePos, serializerVersion, type);                        
        }

        if (valuePos == 0 || fieldLength == 0)
          return null;

        bytes.offset = valuePos;

        Object value = deserializeValue(bytes, type, null, false, fieldDataLength, serializerVersion, false);
        return (RET)value;        
      }
    }
  }
  
  @Override
  public boolean isSerializingClassNameForEmbedded() {
    return true;
  }
  
  @Override
  public Tuple<Integer, OType> getPointerAndTypeFromCurrentPosition(BytesContainer bytes){    
    int fieldSize = OVarIntSerializer.readAsInteger(bytes);
    byte typeId = readByte(bytes);
    OType type = OType.getById(typeId);
    return new Tuple<>(fieldSize, type);
  }    
  
  @Override
  public boolean areTypeAndPointerFlipped(){
    return true;
  }
  
  @Override
  public void deserializeDebug(BytesContainer bytes, ODatabaseDocumentInternal db,
          ORecordSerializationDebug debugInfo, OImmutableSchema schema){
    
    int headerLength = OIntegerSerializer.INSTANCE.deserialize(bytes.bytes, bytes.offset);
    bytes.skip(OIntegerSerializer.INT_SIZE);
    int headerPos = bytes.offset;
    
    debugInfo.properties = new ArrayList<>();
    int last = 0;
    String fieldName;    
    OType type;
    int cumulativeLength = 0;    
    while (true) {
      ORecordSerializationDebugProperty debugProperty = new ORecordSerializationDebugProperty();
      OGlobalProperty prop = null;
      
      int fieldLength;
      
      try {
        if (bytes.offset >= headerPos + headerLength)
          break;
        
        final int len = OVarIntSerializer.readAsInteger(bytes);
//        if (len != 0)
        debugInfo.properties.add(debugProperty);
        if (len > 0) {
          // PARSE FIELD NAME
          fieldName = stringFromBytes(bytes.bytes, bytes.offset, len).intern();
          bytes.skip(len);
                    
          Tuple<Integer, OType> valuePositionAndType = getPointerAndTypeFromCurrentPosition(bytes);
          fieldLength = valuePositionAndType.getFirstVal();
          type = valuePositionAndType.getSecondVal();
        } else {
          // LOAD GLOBAL PROPERTY BY ID
          final int id = (len * -1) - 1;
          debugProperty.globalId = id;
          prop = schema.getGlobalPropertyById(id);
          fieldLength = OVarIntSerializer.readAsInteger(bytes);
          debugProperty.valuePos = headerPos + headerLength + cumulativeLength;
          if (prop != null) {
            fieldName = prop.getName();
            type = getTypeForLenLessThanZero(prop, bytes);
          } else {
            cumulativeLength += fieldLength;
            continue;
          }
        }
        debugProperty.name = fieldName;
        debugProperty.type = type;

        int valuePos;
        if (fieldLength > 0)
          valuePos = headerPos + headerLength + cumulativeLength;
        else
          valuePos = 0;
        
        cumulativeLength += fieldLength;
        
        if (valuePos != 0) {
          int headerCursor = bytes.offset;
          bytes.offset = valuePos;
          try {
            debugProperty.value = deserializeValue(bytes, type, new ODocument());
          } catch (RuntimeException ex) {
            debugProperty.faildToRead = true;
            debugProperty.readingException = ex;
            debugProperty.failPosition = bytes.offset;
          }
          if (bytes.offset > last)
            last = bytes.offset;
          bytes.offset = headerCursor;
        } else
          debugProperty.value = null;
      } catch (RuntimeException ex) {
        debugInfo.readingFailure = true;
        debugInfo.readingException = ex;
        debugInfo.failPosition = bytes.offset;  
      }
    }    
  }
  
}
