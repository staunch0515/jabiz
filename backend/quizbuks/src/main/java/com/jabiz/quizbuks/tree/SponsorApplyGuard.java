package com.jabiz.quizbuks.tree;

import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.security.GuardParam;
import com.jabiz.security.SignInLoad;
import com.jabiz.security.SignUpAttempt;
import com.jabiz.security.SignUpDecision;
import com.jabiz.security.SignUpGuard;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Applying to become a Sponsor from the App (F-S2c2b, D7): a signed-in Taker joins the Sponsor entry only while they
 * have lit the node the controlled parameter {@value QbParams#TREE_GATE_SPONSOR_APPLY} names, by the rule of the other
 * gates ({@link TreeGates#open}: a node in effect of a platform map Takers light, a lit record of that map and node
 * without a revocation). No parameter (the default) is closed. Registration at the Sponsor pages and its mailed join
 * are not gated (D-Q69-9). An operator's own Sponsor (F-S1: its first lead's account) joins without it: that is how
 * the lead gets the Sponsor role.
 *
 * <p>Read by the platform before the check (platform decision D80, design 10 §15.18): the node's row and its map by
 * the parameter's value (not the user's rows), the user's lit records of that map and node, and the user's revocations
 * (a few: one per revoked record).
 */
@Component
class SponsorApplyGuard implements SignUpGuard {

    static final String SPONSOR_ENTRY = "sponsor";
    static final String DIRECT_OF = "directSponsorOf";
    static final String TREE = "gateTree";
    static final String NODE = "gateNode";
    static final String LITS = "gateLits";
    static final String REVOCATIONS = "gateRevocations";
    /** The refusal's code: its texts are {@code SIGN_UP_REFUSED.reason.<code>} in the three languages. */
    static final String REASON = "qb.certification-required";

    private static final String GATE = QbParams.TREE_GATE_SPONSOR_APPLY;
    private static final Function<Object, Object> TREE_OF = value ->
        TreeGates.parse(value).map(TreeGates.GateNode::treeId).orElse(null);
    private static final Function<Object, Object> NODE_OF = value ->
        TreeGates.parse(value).map(TreeGates.GateNode::nodeId).orElse(null);

    @Override
    public List<GuardParam> params() {
        return List.of(GuardParam.optional(GATE));
    }

    @Override
    public List<SignInLoad> loads() {
        return List.of(SignInLoad.of(DIRECT_OF, QbOperators.defaultDataset(QbOperators.OPERATOR), "directSponsorId"),
            SignInLoad.global(TREE, QbTrees.defaultDataset(QbTrees.TREE)).whereParam("treeId", GATE, TREE_OF),
            SignInLoad.global(NODE, QbTrees.defaultDataset(QbTrees.NODE)).whereParam("treeId", GATE, TREE_OF)
                .whereParam("nodeId", GATE, NODE_OF),
            SignInLoad.of(LITS, QbTrees.defaultDataset(QbTrees.LIT), "subjectId").whereParam("treeId", GATE, TREE_OF)
                .whereParam("nodeId", GATE, NODE_OF),
            SignInLoad.of(REVOCATIONS, QbTrees.defaultDataset(QbTrees.REVOCATION), "subjectId"));
    }

    @Override
    public SignUpDecision check(SignUpAttempt attempt) {
        if (!SPONSOR_ENTRY.equals(attempt.entry()) || attempt.path() != SignUpAttempt.Path.SIGNED_IN_JOIN) {
            return SignUpDecision.ALLOW;
        }
        if (!attempt.rows(DIRECT_OF).isEmpty()) {
            return SignUpDecision.ALLOW;
        }
        return open(attempt) ? SignUpDecision.ALLOW : SignUpDecision.refuse(REASON);
    }

    private static boolean open(SignUpAttempt attempt) {
        if (TreeGates.parse(attempt.params().find(GATE, Object.class).orElse(null)).isEmpty()) {
            return false;
        }
        Map<String, Object> tree = first(attempt.rows(TREE));
        Map<String, Object> node = first(attempt.rows(NODE));
        // A Taker's map is lit by people: the user's records of it are theirs as a person (a Sponsor's id is the same
        // account, but a platform map Takers light has no Sponsor's records).
        List<String> lits = attempt.rows(LITS).stream().filter(row -> QbTrees.USER.equals(row.get("subjectKind")))
            .map(row -> String.valueOf(row.get("litId"))).toList();
        List<String> revoked = attempt.rows(REVOCATIONS).stream()
            .filter(row -> QbTrees.USER.equals(row.get("subjectKind")))
            .map(row -> String.valueOf(row.get("litId"))).toList();
        return TreeGates.open(tree == null ? null : new TreeGates.Tree(String.valueOf(tree.get("ownerKind")),
                String.valueOf(tree.get("subjectKind")), String.valueOf(tree.get("status"))),
            node == null ? null : (Boolean) node.get("active"), lits, revoked);
    }

    private static Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
