package com.jabiz.culture;

import com.jabiz.runtime.JabizApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Culture, Unfiltered: a youth-led digital ethnography archive (docs/culture/00-design.md). */
@SpringBootApplication
public class CultureApp {
    public static void main(String[] args) {
        JabizApplication.run(CultureApp.class, args);
    }
}
