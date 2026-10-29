package com.yaprak.build;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationParameters;
import org.objectweb.asm.*;

/** Library diagnostics may contain private book tokens/names. Scope removal to these two parsers. */
public abstract class RedactBookContentLogs implements AsmClassVisitorFactory<InstrumentationParameters.None> {
    @Override public boolean isInstrumentable(ClassData data) {
        return data.getClassName().startsWith("com.tom_roush.") || data.getClassName().startsWith("com.github.junrar.");
    }

    @Override public ClassVisitor createClassVisitor(ClassContext context, ClassVisitor next) {
        return redact(next);
    }

    public static ClassVisitor redact(ClassVisitor next) {
        return new ClassVisitor(Opcodes.ASM9, next) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        boolean android = owner.equals("android/util/Log");
                        boolean rar = owner.equals("org/slf4j/Logger");
                        if (!android && !rar) {
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                            return;
                        }
                        if (android && opcode != Opcodes.INVOKESTATIC || rar && opcode != Opcodes.INVOKEINTERFACE)
                            throw new IllegalStateException("Unexpected book parser log invocation");
                        Type[] args = Type.getArgumentTypes(descriptor);
                        for (int index = args.length - 1; index >= 0; index--)
                            super.visitInsn(args[index].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
                        if (rar) super.visitInsn(Opcodes.POP); // Logger receiver, in addition to every argument.
                        Type result = Type.getReturnType(descriptor);
                        switch (result.getSort()) {
                            case Type.VOID: break;
                            case Type.BOOLEAN: case Type.BYTE: case Type.CHAR: case Type.SHORT: case Type.INT:
                                super.visitInsn(Opcodes.ICONST_0); break;
                            case Type.OBJECT:
                                if (!result.getDescriptor().equals("Ljava/lang/String;"))
                                    throw new IllegalStateException("Unexpected book parser log result");
                                super.visitLdcInsn(""); break;
                            default: throw new IllegalStateException("Unexpected book parser log primitive result");
                        }
                    }
                };
            }
        };
    }
}
