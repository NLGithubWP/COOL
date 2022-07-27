package com.nus.cool.core.cohort.refactor;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.nus.cool.core.cohort.refactor.ageSelect.AgeSelection;
import com.nus.cool.core.cohort.refactor.birthSelect.BirthSelection;
import com.nus.cool.core.cohort.refactor.birthSelect.EventSelection;
import com.nus.cool.core.cohort.refactor.cohortSelect.CohortSelector;
import com.nus.cool.core.cohort.refactor.filter.FilterType;
import com.nus.cool.core.cohort.refactor.filter.Filter;
import com.nus.cool.core.cohort.refactor.storage.CohortRet;
import com.nus.cool.core.cohort.refactor.storage.ProjectedTuple;
import com.nus.cool.core.cohort.refactor.storage.RetUnit;
import com.nus.cool.core.cohort.refactor.storage.Scope;
import com.nus.cool.core.cohort.refactor.utils.DateUtils;
import com.nus.cool.core.cohort.refactor.valueSelect.ValueSelection;
import com.nus.cool.core.io.readstore.ChunkRS;
import com.nus.cool.core.io.readstore.CubeRS;
import com.nus.cool.core.io.readstore.CubletRS;
import com.nus.cool.core.io.readstore.HashMetaFieldRS;
import com.nus.cool.core.io.readstore.MetaChunkRS;
import com.nus.cool.core.io.readstore.MetaFieldRS;
import com.nus.cool.core.io.readstore.UserMetaFieldRS;
import com.nus.cool.core.schema.FieldSchema;
import com.nus.cool.core.schema.FieldType;
import com.nus.cool.core.schema.TableSchema;

import lombok.Getter;
import com.nus.cool.core.cohort.refactor.filter.Filter;

public class CohortProcessor {

    private final AgeSelection ageSelector;

    private final ValueSelection valueSelector;

    private final CohortSelector cohortSelector;

    private final BirthSelection birthSelector;

    @Getter
    private final String dataSource;

    private ProjectedTuple tuple;

    @Getter
    private final CohortRet result;

    private String UserIdSchema;

    private String ActionTimeSchema;

    private final HashSet<String> projectedSchemaSet;

    public CohortProcessor(CohortQueryLayout layout){

        this.ageSelector = layout.getAgetSelectionLayout().generate();
        this.birthSelector = layout.getBirthSelectionLayout().generate();
        this.cohortSelector = layout.getCohortSelectionLayout().generate();
        this.valueSelector = layout.getValueSelectionLayout().generate();

        this.projectedSchemaSet = layout.getSchemaSet();
        this.dataSource = layout.getDataSource();
        this.result =  new CohortRet(layout.getAgetSelectionLayout());
    }


    /**
     * Public interface, Scan whole table and return CohortResult
     *
     * @param cube
     * @return CohortRet
     */
    public CohortRet process(CubeRS cube) throws IOException {
        // initialize the UserId and KeyId
        TableSchema tableschema = cube.getSchema();
        for(FieldSchema fieldSchema : tableschema.getFields()){
            if(fieldSchema.getFieldType() == FieldType.UserKey){
                this.UserIdSchema = fieldSchema.getName();
            } else if (fieldSchema.getFieldType() == FieldType.ActionTime){
                this.ActionTimeSchema = fieldSchema.getName();
            }
        }
        // add this two schema into List
        this.projectedSchemaSet.add(this.UserIdSchema);
        this.projectedSchemaSet.add(this.ActionTimeSchema);
        this.tuple = new ProjectedTuple(this.projectedSchemaSet);
        for (CubletRS cublet : cube.getCublets()) {
            processCublet(cublet);
        }
        return this.result;
    }

    /**
     * Process one Cublet
     *
     * @param cublet
     */
    private void processCublet(CubletRS cublet) {
        MetaChunkRS metaChunk = cublet.getMetaChunk();
        // if it is necessary, add logic in method checkMetaChunk
        // Personally, this step is not universal
        // all right, can check whether this cublet pass rangeFilter
        // for Hash Value, Maintain HashMap<Schema, String[]>, the String[] is gid ->
        // True Value
        HashMap<String, String[]> gidMapBySchema = new HashMap<>();
        HashMap<String, int[]> invariantGidMap = new HashMap<>();

        // we only get used schema;
        UserMetaFieldRS userMetaField;
        for (String schema : this.projectedSchemaSet) {
            MetaFieldRS metaField = metaChunk.getMetaField(schema);
            if(metaField.getFieldType()==FieldType.UserKey){
                userMetaField=(UserMetaFieldRS) metaField;
                gidMapBySchema.put(schema,userMetaField.getGidMap());
                invariantGidMap=loadInvariantGidMaps(userMetaField);
            }
            else if (FieldType.IsHashType(metaField.getFieldType())) {
                gidMapBySchema.put(schema, ((HashMetaFieldRS) metaField).getGidMap());
            }
        }

        if (!this.checkMetaChunk(metaChunk)) {
            return;
        }

        // Now start to pass the DataChunk
        for (ChunkRS chunk : cublet.getDataChunks()) {
            if (this.checkDataChunk(chunk)){
                this.processDataChunk(chunk, metaChunk, gidMapBySchema, invariantGidMap);
            }
        }
    }

    public HashMap<String, int[]> loadInvariantGidMaps(UserMetaFieldRS userMetaField ){
        HashMap<String, int[]> invariantRangeGidMap=new HashMap<>();
        Set<String> invariantName=userMetaField.getInvariantName();
        for( String invariantFieldName : invariantName){
            invariantRangeGidMap.put(invariantFieldName,userMetaField.getInvariantGidMap(invariantFieldName));
        }
        return invariantRangeGidMap;
    }



    /**
     * In this section, we load the tuple which is an inner property.
     * We left the process logic in processTuple function.
     *
     * @param chunk dataChunk
     * @param metaChunk metaChunk
     * @param hashMapperBySchema map of filedName: []value
     * @param invariantGidMap map of invariant filedName: []value
     */
    private void processDataChunk(ChunkRS chunk, MetaChunkRS metaChunk, HashMap<String, String[]> hashMapperBySchema,
                                  HashMap<String, int[]>invariantGidMap) {
        for (int i = 0; i < chunk.getRecords(); i++) {
            // load data into tuple
            for (String schema : this.projectedSchemaSet) {
                // if the value is segment type, we should convert it to String from globalId
                if(chunk.isInvariantFieldByName(schema)){
                    // get the invariant schema from UserMetaField
                    String idName=chunk.getUserFieldName();
                    UserMetaFieldRS userMetaField = (UserMetaFieldRS) metaChunk.getMetaField(idName);
                    int userGlobalId = chunk.getField(idName).getValueByIndex(i);

                    if (FieldType.IsHashType(chunk.getFieldTypeByName(schema))){
                        int hash=invariantGidMap.get(schema)[userGlobalId];
                        int valueGlobalIDLocation= userMetaField.find(hash);
                        String v =metaChunk.getMetaField(schema).getString(valueGlobalIDLocation);
                        tuple.loadAttr(v,schema);
                    }
                    else{
                        int v = invariantGidMap.get(schema)[userGlobalId];
                        tuple.loadAttr(v, schema);
                    }
                }
                else if(hashMapperBySchema.containsKey(schema)){
                    int globalId = chunk.getField(schema).getValueByIndex(i);
                    String v = hashMapperBySchema.get(schema)[globalId];
                    tuple.loadAttr(v, schema);
                } else {
                    tuple.loadAttr(chunk.getField(schema).getValueByIndex(i), schema);
                }
            }
            // now this tuple is loaded
            this.processTuple();
        }
    }

    /**
     * process the inner tuple
     */
    private void processTuple() {
        // For One Tuple, we firstly get the userId, and ActionTime
        String userId = (String) tuple.getValueBySchema(this.UserIdSchema);
        LocalDateTime actionTime = DateUtils.daysSinceEpoch((int)tuple.getValueBySchema(this.ActionTimeSchema));
        // check whether its birthEvent is selected
        if (!this.birthSelector.isUserSelected(userId)) {
            // if birthEvent is not selected
            this.birthSelector.selectEvent(userId, actionTime, this.tuple);
        } else {
            // the birthEvent is selected
            // do time_diff to generate age / get the BirthEvent Date
            LocalDateTime birthTime = this.birthSelector.getUserBirthEventDate(userId);
            int age = this.ageSelector.generateAge(birthTime, actionTime);
            if (age == AgeSelection.DefaultNullAge) {
                // age is outofrange
                return;
            }
            // extract the cohort this tuple belong to
            String cohortName = this.cohortSelector.selectCohort(this.tuple);
            if (cohortName == null) {
                // cohort is outofrange
                return;
            }
            if (!this.valueSelector.IsSelected(this.tuple)) {
                // value outofrange
                return;
            }
            // Pass all above filter, we can store value into CohortRet
            // get the temporay result for this CohortGroup and this age
//            System.out.println("[Update Cohort Result]: cohortName:" + cohortName + "\tage:"+ age);
            RetUnit ret = this.result.getByAge(cohortName, age);
            // update
            this.valueSelector.updateRetUnit(ret, tuple);
        }
    }


    /**
     * Check if this cublet contains the required field.
     * @param metaChunk hashMetaFields result
     * @return true: this metaChunk is valid, false: this metaChunk is invalid.
     */
    private Boolean checkMetaChunk(MetaChunkRS metaChunk){

        // 1. check birth selection
        // if the metaChunk contains all birth filter's accept value, then the metaChunk is valid.
        if ( this.birthSelector.getBirthEvents() == null ){
            return true;
        }

        for (EventSelection es: this.birthSelector.getBirthEvents()){
            for (Filter ft: es.getFilterList()){
                // get schema and type
                String checkedSchema = ft.getFilterSchema();
                MetaFieldRS metaField = metaChunk.getMetaField(checkedSchema);
                if (this.checkMetaField(metaField, ft)){return false;}
            }
        }

        // 2. check birth selection
        Filter cohortFilter = this.cohortSelector.getFilter();
        String checkedSchema = cohortFilter.getFilterSchema();
        MetaFieldRS metaField = metaChunk.getMetaField(checkedSchema);
        if (this.checkMetaField(metaField, cohortFilter)){return false;}

        // 3. check value Selector,
        for (Filter ft: this.valueSelector.getFilterList()){
            String ValueSchema = ft.getFilterSchema();
            MetaFieldRS ValueMetaField = metaChunk.getMetaField(ValueSchema);
            if (this.checkMetaField(ValueMetaField, ft)){return false;}
        }
        return true;
    }

    public Boolean checkMetaField(MetaFieldRS metaField, Filter ft) {
        FilterType checkedType = ft.getType();
        if (checkedType.equals(FilterType.Set)) {
            BitSet res = ft.accept(( (HashMetaFieldRS) metaField).getGidMap());
            // if there is no true in res, then no record meet the requirement
            return res.nextSetBit(0) == -1;
        } else if (checkedType.equals(FilterType.Range)) {
            Scope scope = new Scope(metaField.getMinValue(), metaField.getMaxValue());
            BitSet res = ft.accept(scope);
            // if there is no true in res, then no record meet the requirement
            return res.nextSetBit(0) == -1;
        } else {
            throw new IllegalArgumentException("Only support set or range");
        }
    }

    /***
     *
     * @param chunk data chunk
     * @return if this data chunk need to check
     */
    public Boolean checkDataChunk(ChunkRS chunk){



        return true;
    }


     /**
     * Read from json file and create a instance of CohortProcessor
     * @param in File
     * @return instance of file
     * @throws IOException IOException
     */
    public static CohortProcessor readFromJson(File in) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        CohortProcessor instance = mapper.readValue(in, CohortProcessor.class);
        return instance;
    }


    public static CohortProcessor readFromJson(String path) throws IOException{
        return readFromJson(new File(path));
    }

}