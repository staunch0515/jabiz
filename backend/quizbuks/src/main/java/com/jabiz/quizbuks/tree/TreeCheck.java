package com.jabiz.quizbuks.tree;

import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.tree.TreeRules.Graph;
import com.jabiz.quizbuks.tree.TreeRules.Limits;
import com.jabiz.quizbuks.tree.TreeRules.Problem;
import com.jabiz.quizbuks.tree.TreeRules.Subject;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The knowledge maps in effect follow the rules (F-S2, startup check {@value #LOCATION}): every map's version in
 * effect is checked as its submission was (the three restrictions, known nodes, no cycle through any map, the depth),
 * so that a map put in effect before a rule changed, or written around the processes, is reported. Warnings in the
 * category {@value CountryCatalog#CATEGORY}: the application must start for the maps to be put right. The limits are
 * the parameters' declared values (the processes read the ones in effect).
 *
 * <p>Reads the database itself (JDBC with Flyway's connection settings, as {@code SetupParamsCheck} does), never on
 * the request path; without a database address it is skipped.
 */
@Component
public class TreeCheck implements PlatformCheck {

    static final String LOCATION = "QB_TREE";

    private static final String SQL = "SELECT t.tree_id, t.owner_kind, t.owner_id, t.subject_kind, d.node_id, "
        + "d.condition FROM qb_tree t JOIN qb_tree_node_def d ON d.version_id = t.current_version_id "
        + "WHERE t.status = 'ACTIVE' ORDER BY t.tree_id, d.seq";

    private final Environment environment;

    public TreeCheck(Environment environment) {
        this.environment = environment;
    }

    /** A map in effect as read: who owns it, who lights it, and its nodes' conditions. */
    record Stored(String treeId, String ownerKind, String ownerId, String subjectKind,
        Map<String, TreeCondition> nodes) {}

    @Override
    public List<CheckProblem> check() {
        String url = environment.getProperty("spring.flyway.url");
        if (url == null || url.isBlank()) {
            return List.of();
        }
        List<Stored> maps;
        try (Connection connection = DriverManager.getConnection(url, environment.getProperty("spring.flyway.user"),
            environment.getProperty("spring.flyway.password"))) {
            String schema = schema();
            if (schema != null) {
                connection.setSchema(schema);
            }
            connection.setReadOnly(true);
            maps = read(connection);
        } catch (SQLException | RuntimeException e) {
            return List.of(CheckProblem.warning(CountryCatalog.CATEGORY, LOCATION,
                "the knowledge maps could not be read: " + e.getMessage()));
        }
        Limits limits = new Limits(Integer.parseInt(QbParams.defaultOf(QbParams.TREE_MAX_NODES)),
            Integer.parseInt(QbParams.defaultOf(QbParams.TREE_MAX_CONDITIONS)),
            Integer.parseInt(QbParams.defaultOf(QbParams.TREE_MAX_DEPTH)));
        return problems(maps, limits);
    }

    /** The warnings for each map in effect, checked against the maps it may name. */
    static List<CheckProblem> problems(List<Stored> maps, Limits limits) {
        List<CheckProblem> problems = new ArrayList<>();
        for (Stored map : maps) {
            List<Graph> others = maps.stream().filter(other -> !other.treeId().equals(map.treeId()))
                .filter(other -> mayName(map, other)).map(TreeCheck::graph).toList();
            for (Problem problem : TreeRules.check(graph(map), others, limits)) {
                problems.add(CheckProblem.warning(CountryCatalog.CATEGORY, "knowledge map " + map.treeId()
                    + (problem.nodeId() == null ? "" : " node " + problem.nodeId()),
                    problem.code() + " " + problem.params()));
            }
        }
        return problems;
    }

    /** Which maps a map may name nodes of (as the draft's check: the template {@code qb.system.tree-graphs}). */
    static boolean mayName(Stored map, Stored other) {
        return switch (map.ownerKind()) {
            case QbTrees.SPONSOR -> QbTrees.PLATFORM.equals(other.ownerKind())
                && QbTrees.TAKER.equals(other.subjectKind());
            case QbTrees.PLATFORM -> QbTrees.PLATFORM.equals(other.ownerKind())
                && map.subjectKind().equals(other.subjectKind());
            case QbTrees.OPERATOR -> QbTrees.OPERATOR.equals(other.ownerKind())
                && Objects.equals(map.ownerId(), other.ownerId());
            default -> false;
        };
    }

    private static Graph graph(Stored map) {
        return new Graph(map.treeId(), !QbTrees.SPONSOR.equals(map.ownerKind()), Subject.valueOf(map.subjectKind()),
            map.nodes());
    }

    private static List<Stored> read(Connection connection) throws SQLException {
        Map<String, Stored> maps = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(SQL); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String treeId = rows.getString(1);
                Stored map = maps.computeIfAbsent(treeId, id -> {
                    try {
                        return new Stored(id, rows.getString(2), rows.getString(3), rows.getString(4),
                            new LinkedHashMap<>());
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                });
                map.nodes().put(rows.getString(5), TreeCondition.parse(TreeProcesses.parse(rows.getString(6))));
            }
        }
        return List.copyOf(maps.values());
    }

    private String schema() {
        String schema = environment.getProperty("spring.flyway.default-schema");
        if (schema == null || schema.isBlank()) {
            String schemas = environment.getProperty("spring.flyway.schemas");
            schema = schemas == null ? null : schemas.split(",")[0].strip();
        }
        return schema == null || schema.isBlank() ? null : schema;
    }
}
