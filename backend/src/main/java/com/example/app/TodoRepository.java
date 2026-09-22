package com.example.app;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

interface TodoRepository extends ReactiveCrudRepository<Todo, Long> {}