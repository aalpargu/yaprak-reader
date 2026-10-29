package com.yaprak.build;

import org.junit.Test;
import org.objectweb.asm.*;
import static org.junit.Assert.*;

public class RedactBookContentLogsTest {
    @Test public void transformedBytecodeRunsWithoutAndroidLogAndKeepsReturnContracts() throws Exception {
        ClassWriter source = new ClassWriter(0);
        source.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "LogProbe", null, "java/lang/Object", null);
        MethodVisitor warning = source.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "warning", "()I", null, null);
        warning.visitCode(); warning.visitLdcInsn("PdfBox-Android"); warning.visitLdcInsn("private parsed token");
        warning.visitInsn(Opcodes.ACONST_NULL);
        warning.visitMethodInsn(Opcodes.INVOKESTATIC, "android/util/Log", "w", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I", false);
        warning.visitInsn(Opcodes.IRETURN); warning.visitMaxs(3, 0); warning.visitEnd();
        MethodVisitor enabled = source.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "enabled", "()Z", null, null);
        enabled.visitCode(); enabled.visitLdcInsn("PdfBox-Android"); enabled.visitInsn(Opcodes.ICONST_3);
        enabled.visitMethodInsn(Opcodes.INVOKESTATIC, "android/util/Log", "isLoggable", "(Ljava/lang/String;I)Z", false);
        enabled.visitInsn(Opcodes.IRETURN); enabled.visitMaxs(2, 0); enabled.visitEnd();
        MethodVisitor trace = source.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "trace", "()Ljava/lang/String;", null, null);
        trace.visitCode(); trace.visitInsn(Opcodes.ACONST_NULL);
        trace.visitMethodInsn(Opcodes.INVOKESTATIC, "android/util/Log", "getStackTraceString", "(Ljava/lang/Throwable;)Ljava/lang/String;", false);
        trace.visitInsn(Opcodes.ARETURN); trace.visitMaxs(1, 0); trace.visitEnd(); source.visitEnd();
        ClassWriter output = new ClassWriter(0);
        new ClassReader(source.toByteArray()).accept(RedactBookContentLogs.redact(output), 0);
        byte[] transformed = output.toByteArray();
        final int[] remaining = {0};
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int o, String owner, String n, String d, boolean i) {
                        if (owner.equals("android/util/Log")) remaining[0]++;
                    }
                };
            }
        }, 0);
        assertEquals(0, remaining[0]);
        Class<?> probe = new ClassLoader() { Class<?> load() { return defineClass("LogProbe", transformed, 0, transformed.length); } }.load();
        assertEquals(0, probe.getMethod("warning").invoke(null));
        assertEquals(false, probe.getMethod("enabled").invoke(null));
        assertEquals("", probe.getMethod("trace").invoke(null));
    }

    @Test public void transformedInterfaceLoggingRunsWithNullReceiverAndNoSlf4jClasses() throws Exception {
        ClassWriter source = new ClassWriter(0);
        source.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "RarLogProbe", null, "java/lang/Object", null);
        MethodVisitor warning = source.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "warning", "()V", null, null);
        warning.visitCode(); warning.visitInsn(Opcodes.ACONST_NULL);
        warning.visitLdcInsn("private entry {} failed"); warning.visitLdcInsn("private entry name");
        warning.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/slf4j/Logger", "warn", "(Ljava/lang/String;Ljava/lang/Object;)V", true);
        warning.visitInsn(Opcodes.RETURN); warning.visitMaxs(3, 0); warning.visitEnd();
        MethodVisitor enabled = source.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "enabled", "()Z", null, null);
        enabled.visitCode(); enabled.visitInsn(Opcodes.ACONST_NULL);
        enabled.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/slf4j/Logger", "isInfoEnabled", "()Z", true);
        enabled.visitInsn(Opcodes.IRETURN); enabled.visitMaxs(1, 0); enabled.visitEnd(); source.visitEnd();
        ClassWriter output = new ClassWriter(0);
        new ClassReader(source.toByteArray()).accept(RedactBookContentLogs.redact(output), 0);
        byte[] transformed = output.toByteArray();
        final int[] remaining = {0};
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int o, String owner, String n, String d, boolean i) {
                        if (owner.equals("org/slf4j/Logger")) remaining[0]++;
                    }
                };
            }
        }, 0);
        assertEquals(0, remaining[0]);
        Class<?> probe = new ClassLoader() { Class<?> load() { return defineClass("RarLogProbe", transformed, 0, transformed.length); } }.load();
        probe.getMethod("warning").invoke(null);
        assertEquals(false, probe.getMethod("enabled").invoke(null));
    }
}
