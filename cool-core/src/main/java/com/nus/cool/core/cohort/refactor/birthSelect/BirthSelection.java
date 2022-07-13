package com.nus.cool.core.cohort.refactor.birthSelect;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.nus.cool.core.cohort.refactor.storage.ProjectedTuple;
import com.nus.cool.core.cohort.refactor.utils.TimeWindow;

import lombok.Getter;
import lombok.Setter;

@Getter
public class BirthSelection {

    private List<EventSelection> birthEvents;
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private TimeWindow timeWindow;

    @JsonIgnore
    private BirthSelectionContext context;

    @JsonIgnore
    @Getter
    private HashSet<String> relatedSchemas;

    /**
     * initialize a instance of BirthSelection load from json
     * transfer filter layout to filter instance.
     */
    public void init() {
        int[] eventMinFrequency = new int[birthEvents.size()];
        this.relatedSchemas = new HashSet<>();
        for (int i = 0; i < birthEvents.size(); i++) {
            birthEvents.get(i).init();
            eventMinFrequency[i] = birthEvents.get(i).getFrequency();
            this.relatedSchemas.addAll(birthEvents.get(i).getSchemaList());
        }
        this.context = new BirthSelectionContext(this.timeWindow, eventMinFrequency);
    }

    /**
     * If user's birthEvent is selected return true else return false
     * 
     * @param userId
     * @return
     */
    public boolean isUserSelected(String userId) {
        return context.IsUserSelected(userId);
    }

    /**
     * If user's birthEvent is selected
     * Get the BirthEvent Date to generate "Age" in Cohort
     * 
     * @param userId
     * @return
     */
    public LocalDateTime getUserBirthEventDate(String userId) {
        return context.getUserBirthEventDate(userId);
    }

    /**
     * Select input Action Tuple, if it can be selected as event return true else
     * return false
     * @param userId
     * @param date
     * @param tuple  Partial Action Tuple,
     * @return
     */
    public boolean selectEvent(String userId, LocalDateTime date, ProjectedTuple tuple) {
        int eventIdx = 0;
        for (EventSelection event : birthEvents) {
            if (event.Accept(tuple)) {
                context.Add(userId, eventIdx, date);
                return true;
            }
            eventIdx++;
        }
        return false;
    }


    // -------------- the method is for UnitTest and Debug -------------- //
    // public static BirthSelection readFromJson(File in) throws IOException {
    //     ObjectMapper objectMapp
    // }

    /**
     * 
     * @return usersId which is eligiable for conditions
     */
    public Set<String> getAcceptedUsers(){
        return this.context.getSelectedUserId();
    }
}
