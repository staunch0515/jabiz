package com.example.app;

import jakarta.validation.constraints.NotBlank;

record CreateTodo(@NotBlank String title) {}