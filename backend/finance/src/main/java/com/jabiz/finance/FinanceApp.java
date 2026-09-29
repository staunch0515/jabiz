package com.jabiz.finance;

import com.jabiz.runtime.JabizApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** A basic US finance system for one company (docs/finance/00-design.md, docs/finance-requirements/). */
@SpringBootApplication
public class FinanceApp {
    public static void main(String[] args) {
        JabizApplication.run(FinanceApp.class, args);
    }
}
