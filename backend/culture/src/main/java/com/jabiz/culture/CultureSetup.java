package com.jabiz.culture;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.param.ParamEntities;
import com.jabiz.runtime.param.ParamPermissions;
import com.jabiz.runtime.param.ParamProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.security.SecurityPermissions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.jabiz.culture.Culture.*;
import static com.jabiz.culture.Workflow.rows;
import static com.jabiz.culture.Workflow.where;

/**
 * {@code CULTURE_SETUP} (docs/culture/00-design.md section 6.6): creates what the application needs in the platform's
 * own data, once, after the first deployment: the roles {@code CURATOR} and {@code CORRESPONDENT} with their
 * permissions, the admin menu and the two switches (the database dictionaries are base data of the migration
 * {@code V3__culture_dictionaries.sql}, since startup checks that they exist). It only adds what does
 * not exist yet, so running it again changes nothing (an administrator's later changes to the roles are kept).
 */
@Configuration
public class CultureSetup {

    public static final String SETUP = "CULTURE_SETUP";

    public static final String CURATOR = "CURATOR";
    public static final String CORRESPONDENT = "CORRESPONDENT";

    public static final List<String> CURATOR_PERMISSIONS = List.of(CONTENT_READ, CONTENT_WRITE, STORY_REVIEW,
        STORY_PUBLISH, STORY_UNPUBLISH, PARTICIPANT_MANAGE, CONSENT_READ, CONSENT_WRITE, CONSENT_WITHDRAW,
        RESOURCE_WRITE, RESOURCE_PUBLISH, MEDIA_UPLOAD, MEDIA_READ, PUBLIC_READ, ParamPermissions.READ);

    public static final List<String> CORRESPONDENT_PERMISSIONS = List.of(OWN_READ, OWN_WRITE, STORY_SUBMIT,
        MEDIA_UPLOAD, MEDIA_READ);

    /** The input carries nothing: everything set up is fixed by the application. */
    public record SetupInput() {}

    /** How many of each were created by this run; all zero when everything existed already. */
    public record SetupOutput(int roles, int permissions, int menus, int params) {}

    private record Role(String code, Map<String, String> labels, List<String> permissions) {}

    private record Menu(String code, String parent, Map<String, String> labels, String path, int order,
        String permission) {}

    private record Param(String key, boolean value, String description) {}

    private static Map<String, String> labels(String zh, String ja, String en) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("zh", zh);
        labels.put("ja", ja);
        labels.put("en", en);
        return labels;
    }

    private static final List<Role> ROLES = List.of(
        new Role(CURATOR, labels("编辑", "編集者", "Curator"), CURATOR_PERMISSIONS),
        new Role(CORRESPONDENT, labels("通讯员", "特派員", "Correspondent"), CORRESPONDENT_PERMISSIONS));

    static String datasetPath(String datasetId) {
        return "/data/" + URLEncoder.encode(datasetId, StandardCharsets.UTF_8);
    }

    static final List<Menu> MENUS = List.of(
        new Menu("culture.content", null, labels("内容", "コンテンツ", "Content"), null, 100, CONTENT_READ),
        new Menu("culture.content.stories", "culture.content", labels("故事", "ストーリー", "Stories"),
            datasetPath(dataset(STORY)), 110, CONTENT_READ),
        new Menu("culture.content.contributions", "culture.content", labels("视角", "視点", "Perspectives"),
            datasetPath(dataset(CONTRIBUTION)), 120, CONTENT_READ),
        new Menu("culture.content.media", "culture.content", labels("照片与音频", "写真と音声", "Photos and audio"),
            datasetPath(dataset(MEDIA_ITEM)), 130, CONTENT_READ),
        new Menu("culture.content.themes", "culture.content", labels("主题", "テーマ", "Themes"),
            datasetPath(dataset(THEME)), 140, CONTENT_READ),
        new Menu("culture.content.locations", "culture.content", labels("地点", "場所", "Places"),
            datasetPath(dataset(LOCATION)), 150, CONTENT_READ),
        new Menu("culture.content.resources", "culture.content", labels("教学资源", "教材", "Resources"),
            datasetPath(dataset(RESOURCE)), 160, CONTENT_READ),
        new Menu("culture.content.blocks", "culture.content", labels("文案块", "テキストブロック", "Site texts"),
            datasetPath(dataset(SITE_BLOCK)), 170, CONTENT_READ),
        new Menu("culture.people", null, labels("人员", "参加者", "People"), null, 200, CONTENT_READ),
        new Menu("culture.people.participants", "culture.people", labels("参与者", "参加者", "Participants"),
            datasetPath(dataset(PARTICIPANT)), 210, CONTENT_READ),
        new Menu("culture.people.consents", "culture.people", labels("同意记录", "同意記録", "Consent records"),
            datasetPath(dataset(CONSENT)), 220, CONSENT_READ),
        new Menu("culture.settings", null, labels("设置", "設定", "Settings"), null, 300, ParamPermissions.READ),
        new Menu("culture.settings.switches", "culture.settings", labels("开关", "スイッチ", "Switches"),
            datasetPath(ParamEntities.DATASET), 310, ParamPermissions.READ),
        new Menu("culture.mine", null, labels("我的内容", "自分のコンテンツ", "My content"), null, 400, OWN_READ),
        new Menu("culture.mine.profile", "culture.mine", labels("我的资料", "自分のプロフィール", "My profile"),
            datasetPath(ownView(PARTICIPANT)), 410, OWN_READ),
        new Menu("culture.mine.stories", "culture.mine", labels("我的故事", "自分のストーリー", "My stories"),
            datasetPath(ownView(STORY)), 420, OWN_READ),
        new Menu("culture.mine.drafts", "culture.mine", labels("我的草稿", "自分の下書き", "My drafts"),
            datasetPath(Culture.own(STORY)), 425, OWN_WRITE),
        new Menu("culture.mine.contributions", "culture.mine", labels("我的视角", "自分の視点", "My perspectives"),
            datasetPath(Culture.own(CONTRIBUTION)), 430, OWN_WRITE),
        new Menu("culture.mine.media", "culture.mine", labels("我的照片", "自分の写真", "My photos"),
            datasetPath(Culture.own(MEDIA_ITEM)), 440, OWN_WRITE));

    static final List<Param> PARAMS = List.of(
        new Param(REVIEW_REQUIRED, true, "Whether an editor reviews a correspondent's story before it is published."),
        new Param(GUARDIAN_REQUIRED, true, "Whether participants under 18 need a guardian's consent record."));

    private static final String EXISTING_ROLES = "existingRoles";
    private static final String EXISTING_MENUS = "existingMenus";
    private static final String EXISTING_PARAMS = "existingParams";
    private static final String NEW_PARAMS = "newParams";
    private static final String OUTPUT = "output";

    public static final ProcessDefinition<SetupInput, SetupOutput, ProcessContext> SETUP_PROCESS =
        ProcessDefinition.define(SETUP, 1, SetupInput.class, SetupOutput.class, ProcessContext.class, pb -> pb
            .description("Creates the roles, menu and switches of Culture, Unfiltered; "
                + "adds only what is missing.")
            .permissions(SecurityPermissions.ROLE_WRITE, SecurityPermissions.MENU_WRITE, ParamPermissions.WRITE)
            .contextFactory((start, in) -> new ProcessContext(start))
            .outputMapper(ctx -> ctx.get(OUTPUT, SetupOutput.class))
            .step("Load the roles", QueryEntities.of(SecurityEntities.ROLE_DATASET,
                ctx -> where("roleCode", ROLES.stream().map(Role::code).toList()), EXISTING_ROLES))
            .step("Load the menu", QueryEntities.of(SecurityEntities.MENU_DATASET,
                ctx -> where("menuCode", MENUS.stream().map(Menu::code).toList()), EXISTING_MENUS))
            .step("Load the switches", QueryEntities.of(ParamEntities.DATASET,
                ctx -> where(ParamEntities.KEY, PARAMS.stream().map(Param::key).toList()), EXISTING_PARAMS))
            .compute("Add what is missing", (metadata, ctx) -> setUp(ctx))
            .step("Create the switches", CallProcess.forEach(ParamProcesses.CREATE.name(), 1,
                ctx -> ctx.get(NEW_PARAMS, List.class), null)));

    private static Set<String> codes(ProcessContext ctx, String key, String field) {
        return rows(ctx, key).stream().map(row -> String.valueOf((Object) row.get(field))).collect(Collectors.toSet());
    }

    private static void setUp(ProcessContext ctx) {
        int roles = 0;
        int permissions = 0;
        Set<String> existingRoles = codes(ctx, EXISTING_ROLES, "roleCode");
        for (Role role : ROLES) {
            if (existingRoles.contains(role.code())) {
                continue;
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("roleCode", role.code());
            attributes.put("labels", role.labels());
            attributes.put("enabled", true);
            Object roleId = ctx.changes().insert(SecurityEntities.ROLE, attributes);
            roles++;
            for (String permission : role.permissions()) {
                ctx.changes().insert(SecurityEntities.ROLE_PERMISSION, Map.of("roleId", roleId,
                    "permission", permission));
                permissions++;
            }
        }

        int menus = 0;
        Set<String> existingMenus = codes(ctx, EXISTING_MENUS, "menuCode");
        for (Menu menu : MENUS) {
            if (existingMenus.contains(menu.code())) {
                continue;
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("menuCode", menu.code());
            attributes.put("parentCode", menu.parent());
            attributes.put("labels", menu.labels());
            attributes.put("path", menu.path());
            attributes.put("sortOrder", menu.order());
            attributes.put("permission", menu.permission());
            attributes.put("enabled", true);
            ctx.changes().insert(SecurityEntities.MENU, attributes);
            menus++;
        }

        Set<String> existingParams = codes(ctx, EXISTING_PARAMS, ParamEntities.KEY);
        List<ParamProcesses.CreateInput> newParams = new ArrayList<>();
        for (Param param : PARAMS) {
            if (!existingParams.contains(param.key())) {
                newParams.add(new ParamProcesses.CreateInput(param.key(), Map.of("type", "bool"), param.value(),
                    param.description()));
            }
        }
        ctx.put(NEW_PARAMS, List.copyOf(newParams));
        ctx.put(OUTPUT, new SetupOutput(roles, permissions, menus, newParams.size()));
    }

    @Bean
    ProcessDefinition<SetupInput, SetupOutput, ProcessContext> cultureSetupProcess() {
        return SETUP_PROCESS;
    }
}
