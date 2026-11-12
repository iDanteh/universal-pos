package com.posplatform.shared;

import com.posplatform.shared.domain.MexicanTaxId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MexicanTaxIdTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "GME800410HM2",          // persona física
            "ECA1309101A5",          // persona moral
            "GME800410HM1"           // home-ended (en trámite)
    })
    void wellFormedRfcsAreAccepted(String rfc) {
        assertThat(MexicanTaxId.isWellFormed(rfc)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "GME80041",        // incomplete
            "GME800410HM25",   // extra character
            "12E800410HM2",    // digits in the leading part
            "GME800410 H2",    // space
            "gme800410hm2",    // lowercase must be normalized first
            "GME-800410-HM2"   // separators
    })
    void malformedRfcsAreRejected(String rfc) {
        assertThat(MexicanTaxId.isWellFormed(rfc)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void blankIsNotWellFormedButNormalizeIsNullSafe(String blank) {
        // normalize: null-safe; the empty string stays "" (caller treats blank as absent)
        assertThat(MexicanTaxId.normalize(blank))
                .satisfiesAnyOf(
                        value -> assertThat(value).isNull(),
                        value -> assertThat((CharSequence) value).isBlank());
        assertThat(MexicanTaxId.isWellFormed(blank)).isFalse();
    }

    @Test
    void normalizeUppercasesAndTrims() {
        assertThat(MexicanTaxId.normalize("  gme800410hm2 ")).isEqualTo("GME800410HM2");
    }

    @Test
    void requireValidChecksShapeAfterNormalization() {
        assertThat(MexicanTaxId.requireValid(" gme800410hm2 ")).isEqualTo("GME800410HM2");
        assertThatThrownBy(() -> MexicanTaxId.requireValid("not-an-rfc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RFC mal formado");
    }

    @Test
    void requireValidAcceptsNullAsOptionalValue() {
        // null = the caller did not provide a tax id (optional field) — no throw
        assertThat(MexicanTaxId.normalize(null)).isNull();
        assertThatThrownBy(() -> MexicanTaxId.requireValid(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
