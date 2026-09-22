package com.example.app;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/todos")
class TodoController {

    private final TodoRepository repo;

    TodoController(TodoRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    Flux<Todo> list() {
        return repo.findAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Mono<Todo> create(@Valid @RequestBody CreateTodo req) {
        return repo.save(new Todo(null, req.title(), false));
    }

    @PutMapping("/{id}/toggle")
    Mono<Todo> toggle(@PathVariable long id) {
        return repo.findById(id)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
            .flatMap(t -> repo.save(new Todo(t.id(), t.title(), !t.done())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> delete(@PathVariable long id) {
        return repo.deleteById(id);
    }
}