package com.confia.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link InvalidMoneyAmountException}'s three causes (design.md, decision 2). Its package-private
 * factories deliberately never take the rejected raw input as a parameter, which is what
 * structurally guarantees none of its messages can ever repeat it (CLAUDE.md, rule 11).
 */
class InvalidMoneyAmountExceptionTest {

    @Test
    void extendsDomainException() {
        assertThat(InvalidMoneyAmountException.malformed()).isInstanceOf(DomainException.class);
    }

    @Test
    void malformedHasTheMalformedCode() {
        assertThat(InvalidMoneyAmountException.malformed().code())
                .isEqualTo("money-amount-malformed");
        assertThat(InvalidMoneyAmountException.MALFORMED).isEqualTo("money-amount-malformed");
    }

    @Test
    void scaleExceededHasTheScaleExceededCode() {
        assertThat(InvalidMoneyAmountException.scaleExceeded(6).code())
                .isEqualTo("money-scale-exceeded");
        assertThat(InvalidMoneyAmountException.SCALE_EXCEEDED).isEqualTo("money-scale-exceeded");
    }

    @Test
    void scaleExceededMessageStatesTheStructuralFactWithoutTheRejectedInput() {
        assertThat(InvalidMoneyAmountException.scaleExceeded(6).getMessage()).contains("6");
    }

    @Test
    void outOfRangeHasTheOutOfRangeCode() {
        assertThat(InvalidMoneyAmountException.outOfRange().code())
                .isEqualTo("money-amount-out-of-range");
        assertThat(InvalidMoneyAmountException.OUT_OF_RANGE)
                .isEqualTo("money-amount-out-of-range");
    }
}
