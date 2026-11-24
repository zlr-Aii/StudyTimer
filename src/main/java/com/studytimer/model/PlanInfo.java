
package com.studytimer.model;

public class PlanInfo {

    private String id;
    private String name;

    // 计划描述
    private String description = "";

    // 计划分类
    private String category = "未分类";

    public PlanInfo() {
    }

    // 保留原来的构造方法，避免影响已有代码
    public PlanInfo(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description == null ? "" : description;
    }

    public void setDescription(String description) {
        this.description = description == null ? "" : description;
    }

    public String getCategory() {
        return category == null || category.isBlank()
                ? "未分类"
                : category;
    }

    public void setCategory(String category) {
        this.category = category == null || category.isBlank()
                ? "未分类"
                : category.trim();
    }
}
