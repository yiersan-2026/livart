package com.artisanlab.canvas;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;
import java.util.UUID;

@TableName("artisan_canvas_snapshots")
public class CanvasSnapshotEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;

    @TableField("canvas_id")
    private UUID canvasId;

    @TableField("state_json")
    private String stateJson;

    @TableField("created_at")
    private OffsetDateTime createdAt;

    private Long revision;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getCanvasId() {
        return canvasId;
    }

    public void setCanvasId(UUID canvasId) {
        this.canvasId = canvasId;
    }

    public String getStateJson() {
        return stateJson;
    }

    public void setStateJson(String stateJson) {
        this.stateJson = stateJson;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Long getRevision() {
        return revision;
    }

    public void setRevision(Long revision) {
        this.revision = revision;
    }
}
