package com.jabiz.app;

import com.jabiz.runtime.JabizApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class App {
    public static void main(String[] args) {
        JabizApplication.run(App.class, args);
    }
}
