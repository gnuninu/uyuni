package com.suse.manager.webui.websocket.json;

/**
 * Created by matei on 9/28/16.
 */
public abstract class RemoteSaltCommandEventDto {

    private String type;

    private String minion;

    private String actionType;

    public RemoteSaltCommandEventDto(String type) {
        this.type = type;
    }

    public RemoteSaltCommandEventDto(String type, String minion) {
        this.type = type;
        this.minion = minion;
    }

    public RemoteSaltCommandEventDto(String type, String minion, String actionType) {
        this.type = type;
        this.minion = minion;
        this.actionType = actionType;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getMinion() {
        return minion;
    }

    public void setMinion(String minion) {
        this.minion = minion;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }
}
