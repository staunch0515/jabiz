plugins {
    `java-library`
}

// The engine's channel contract (docs/quizbuks/shopify/S2-storefront.md section 1): the names, inputs and outputs of
// the processes through which a merchant system of its own (QuizBuks for Shopify) publishes on the engine and has its
// takers answer. Records and constants only. The engine (:quizbuks) implements the processes; a channel's module
// (:quizbuks-shopify) calls them as sub-processes with these inputs, never reaching the engine's classes.
base {
    archivesName.set("quizbuks-channel")
}

dependencies {
    api(project(":runtime"))
}
