package com.jabiz.runtime.check;

import com.jabiz.runtime.JabizApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.PrintStream;
import java.util.List;

/**
 * The {@code platformCheck} build task (docs/design/07-quality.md section 2): starts the application without a web
 * server against a migrated database, runs every {@link PlatformCheck} the application runs at startup, prints one
 * line per problem ({@code category | location | description}) and returns a non-zero exit code if any is an error.
 * A context that cannot even be created is reported the same way.
 */
public final class PlatformCheckMain {

    private PlatformCheckMain() {}

    /**
     * @param application the application's {@code @SpringBootApplication} class
     * @param args        Spring Boot arguments, such as {@code --spring.r2dbc.url=...}
     * @return the exit code: 0 when no errors were found
     */
    public static int run(Class<?> application, List<String> args, PrintStream out) {
        if (System.getProperty(JabizApplication.VIRTUAL_THREADS_PROPERTY) == null) {
            System.setProperty(JabizApplication.VIRTUAL_THREADS_PROPERTY, "true");
        }
        List<CheckProblem> problems;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(application)
            .web(WebApplicationType.NONE)
            .properties(PlatformCheckRunner.ON_STARTUP_PROPERTY + "=false", "spring.main.banner-mode=off")
            .run(args.toArray(String[]::new))) {
            problems = context.getBean(PlatformCheckRunner.class).runAll();
        } catch (RuntimeException e) {
            problems = List.of(CheckProblem.error("CONTEXT", "-", "the application context cannot start: "
                + rootMessage(e)));
        }
        long errors = problems.stream().filter(CheckProblem::isError).count();
        problems.forEach(problem -> out.println(problem.format()));
        out.println("platformCheck: " + errors + " error(s), " + (problems.size() - errors) + " warning(s)");
        return errors == 0 ? 0 : 1;
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getName() : cause.getMessage();
    }

    /** {@code args[0]} is the application class, the rest are Spring Boot arguments. */
    public static void main(String[] args) throws ClassNotFoundException {
        if (args.length == 0) {
            System.err.println("usage: PlatformCheckMain <application class> [--property=value ...]");
            System.exit(2);
        }
        Class<?> application = Class.forName(args[0]);
        System.exit(run(application, List.of(args).subList(1, args.length), System.out));
    }
}
