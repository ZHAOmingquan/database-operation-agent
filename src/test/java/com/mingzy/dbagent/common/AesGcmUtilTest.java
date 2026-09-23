package com.mingzy.dbagent.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AesGcmUtilTest {

    private static final String KEY = "VrwqJyyH8fRw0LlA+RRoyAaMIWr2bwOCWJDqv8V2xaU=";

    @Test
    void encryptDecryptRoundTrip() {
        AesGcmUtil util = new AesGcmUtil(KEY);
        String plain = "Aa123456.";
        String cipher = util.encrypt(plain);
        assertThat(cipher).isNotEqualTo(plain);
        assertThat(util.decrypt(cipher)).isEqualTo(plain);
    }

    @Test
    void samePlaintextProducesDifferentCiphertext() {
        AesGcmUtil util = new AesGcmUtil(KEY);
        assertThat(util.encrypt("same")).isNotEqualTo(util.encrypt("same"));
    }
}
