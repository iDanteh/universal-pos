package com.posplatform.shared;

import com.posplatform.shared.domain.UuidV7;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {

    @Test
    void generatesVersion7Uuids() {
        for (int i = 0; i < 50; i++) {
            var value = UuidV7.next().toString();
            // version nibble: 8-4-4-4-12 groups; group 3 starts with 7
            assertThat(value.split("-")[2]).startsWith("7");
            // variant nibble: group 4 must be 8,9,a or b
            assertThat(value.split("-")[3].charAt(0)).isIn('8', '9', 'a', 'b');
        }
    }

    @Test
    void generatesDistinctValues() {
        long distinct = java.util.stream.IntStream.range(0, 1000)
                .mapToObj(i -> UuidV7.next())
                .distinct()
                .count();
        assertThat(distinct).isEqualTo(1000);
    }

    @Test
    void isRoughlyTimeOrdered() {
        long earlier = UuidV7.next().getMostSignificantBits() >>> 16;
        long later = UuidV7.next().getMostSignificantBits() >>> 16;
        assertThat(later).isGreaterThanOrEqualTo(earlier);
    }
}
