package com.jabiz.quizbuks;

import com.jabiz.runtime.JabizApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** QuizBuks: sponsors fund quizzes, people answer them and are rewarded in Kudos (docs/quizbuks/02-design.md). */
@SpringBootApplication
public class QuizbuksApp {
    public static void main(String[] args) {
        JabizApplication.run(QuizbuksApp.class, args);
    }
}
