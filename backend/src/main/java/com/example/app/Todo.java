package com.example.app;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("todo")
public record Todo(@Id Long id, String title, boolean done) {
    public Todo withId(Long id) {          // 不可变实体回填生成的主键需要 wither
        return new Todo(id, title, done);
    }
}