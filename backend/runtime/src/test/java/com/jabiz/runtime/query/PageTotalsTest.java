package com.jabiz.runtime.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageTotalsTest {

    @Test
    void aPageNotFilledTellsTheTotal() {
        assertThat(PageTotals.known(0, 5, 3)).contains(3L);
        assertThat(PageTotals.known(0, 5, 0)).contains(0L);
        assertThat(PageTotals.known(5, 5, 2)).contains(7L);
    }

    @Test
    void aFullPageOrAnEmptyOnePastTheFirstDoesNot() {
        assertThat(PageTotals.known(0, 5, 5)).isEmpty();
        assertThat(PageTotals.known(20, 5, 0)).isEmpty();
    }
}
