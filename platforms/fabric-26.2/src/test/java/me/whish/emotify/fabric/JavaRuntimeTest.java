package me.whish.emotify.fabric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class JavaRuntimeTest {
    @Test
    void usesJava25() {
        assertEquals(25, Runtime.version().feature());
    }
}
