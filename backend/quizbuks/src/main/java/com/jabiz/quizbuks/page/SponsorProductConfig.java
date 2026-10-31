package com.jabiz.quizbuks.page;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.operator.OperatorScopes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.jabiz.quizbuks.page.SponsorProductProcesses.ProductOutput;
import static com.jabiz.quizbuks.page.SponsorProductProcesses.ProductRef;
import static com.jabiz.quizbuks.page.SponsorProductProcesses.TakedownInput;

/**
 * Registers the sponsors' products (Q60b): the entities, the administrators' datasets (operators: their own sponsors',
 * Q59c-2; written by the processes only), the sponsor's own, the public ones (read through the public templates,
 * which show only a published page's listed products), and the processes.
 */
@Configuration
public class SponsorProductConfig {

    /** Which quiz a version is of, for the public templates (from a live card's version to the quiz's products). */
    public static final String PUBLIC_QUIZ_VERSION_DATASET = "urn:jabiz:dataset:public:" + QbContent.VERSION;

    private final String poolRef;

    SponsorProductConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    EntityDefinition qbSponsorProductEntity() {
        return QbSponsorProducts.PRODUCT_ENTITY;
    }

    @Bean
    EntityDefinition qbQuizProductEntity() {
        return QbSponsorProducts.LINK_ENTITY;
    }

    @Bean
    DatasetDefinition qbSponsorProductDataset() {
        return DatasetDefinition.define(QbSponsorProducts.DEFAULT_DATASET, d -> d
            .targetEntityType(QbSponsorProducts.PRODUCT)
            .asDefault()
            .permissions(QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .scope(OperatorScopes.of(QbSponsorProducts.PRODUCT))
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    DatasetDefinition qbQuizProductDataset() {
        return DatasetDefinition.define(QbSponsorProducts.LINK_DATASET, d -> d
            .targetEntityType(QbSponsorProducts.QUIZ_LINK)
            .asDefault()
            .permissions(QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .scope(OperatorScopes.of(QbSponsorProducts.QUIZ_LINK))
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    DatasetDefinition qbMySponsorProductDataset() {
        return DatasetDefinition.define(QbSponsorProducts.MY_DATASET, d -> d
            .targetEntityType(QbSponsorProducts.PRODUCT)
            .permissions(QbPermissions.SPONSOR_ME, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("userId", "actorId", RequestContext::actorId)));
    }

    @Bean
    DatasetDefinition qbMyQuizProductDataset() {
        return DatasetDefinition.define(QbSponsorProducts.MY_LINK_DATASET, d -> d
            .targetEntityType(QbSponsorProducts.QUIZ_LINK)
            .permissions(QbPermissions.SPONSOR_ME, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("ownerId", "actorId", RequestContext::actorId)));
    }

    /**
     * The listed products, anonymously through the public templates, which show only those of a published page (they
     * join it). Their images are public files thereby: random keys, uploaded by the sponsor to show. The dataset API is
     * closed to everybody (no role holds the permission).
     */
    @Bean
    DatasetDefinition qbPublicSponsorProductDataset() {
        return DatasetDefinition.define(QbSponsorProducts.PUBLIC_DATASET, d -> d
            .targetEntityType(QbSponsorProducts.PRODUCT)
            .scope(s -> s.fixed("status", QbSponsorProducts.LISTED))
            .publicRead(p -> p.fields("productId", "userId", "name", "description", "image", "price", "currency",
                "shopUrl", "seq"))
            .permissions(QbPermissions.SPONSOR_VIEW_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** Which products a quiz and its questions show: keys and numbers only. */
    @Bean
    DatasetDefinition qbPublicQuizProductDataset() {
        return DatasetDefinition.define(QbSponsorProducts.PUBLIC_LINK_DATASET, d -> d
            .targetEntityType(QbSponsorProducts.QUIZ_LINK)
            .publicRead(p -> p.allRows().fields("quizId", "questionNo", "productId", "ownerId", "seq"))
            .permissions(QbPermissions.SPONSOR_VIEW_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** Which quiz a version is of: keys only, nothing of the content. */
    @Bean
    DatasetDefinition qbPublicQuizVersionDataset() {
        return DatasetDefinition.define(PUBLIC_QUIZ_VERSION_DATASET, d -> d
            .targetEntityType(QbContent.VERSION)
            .publicRead(p -> p.allRows().fields("versionId", "quizId"))
            .permissions(QbPermissions.SPONSOR_VIEW_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    StaticDictionary qbSponsorProductStatusDictionary() {
        return StaticDictionary.define(QbSponsorProducts.STATUS_DICTIONARY, d -> d
            .item(QbSponsorProducts.LISTED, "en", "Shown", "zh", "上架", "ja", "表示中")
            .item(QbSponsorProducts.UNLISTED, "en", "Hidden", "zh", "未上架", "ja", "非表示")
            .item(QbSponsorProducts.TAKEN_DOWN, "en", "Taken down", "zh", "已下架", "ja", "公開停止"));
    }

    @Bean
    ProcessDefinition<SponsorProductProcesses.SaveInput, ProductOutput, ProcessContext> qbProductSave() {
        return SponsorProductProcesses.save();
    }

    @Bean
    ProcessDefinition<ProductRef, ProductOutput, ProcessContext> qbProductDelete() {
        return SponsorProductProcesses.delete();
    }

    @Bean
    ProcessDefinition<SponsorProductProcesses.QuizProductsInput, SponsorProductProcesses.QuizProductsOutput,
        ProcessContext> qbQuizProductsSet() {
        return SponsorProductProcesses.quizProducts();
    }

    @Bean
    ProcessDefinition<TakedownInput, ProductOutput, ProcessContext> qbProductTakedown() {
        return SponsorProductProcesses.takedown();
    }

    @Bean
    ProcessDefinition<TakedownInput, ProductOutput, ProcessContext> qbOperatorProductTakedown() {
        return SponsorProductProcesses.operatorTakedown();
    }

    @Bean
    ProcessDefinition<ProductRef, ProductOutput, ProcessContext> qbProductRestore() {
        return SponsorProductProcesses.restore();
    }

    @Bean
    ProcessDefinition<ProductRef, ProductOutput, ProcessContext> qbOperatorProductRestore() {
        return SponsorProductProcesses.operatorRestore();
    }
}
