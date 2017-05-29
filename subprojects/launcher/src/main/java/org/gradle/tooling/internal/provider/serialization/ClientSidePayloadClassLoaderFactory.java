/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.tooling.internal.provider.serialization;

import org.gradle.internal.classloader.ClassLoaderSpec;
import org.gradle.internal.classloader.TransformingClassLoader;
import org.gradle.internal.classloader.VisitableURLClassLoader;
import org.gradle.tooling.provider.model.internal.LegacyConsumerInterface;
import org.gradle.util.internal.Java9ClassReader;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.net.URL;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ClientSidePayloadClassLoaderFactory implements PayloadClassLoaderFactory {
    private final PayloadClassLoaderFactory classLoaderFactory;

    public ClientSidePayloadClassLoaderFactory(PayloadClassLoaderFactory classLoaderFactory) {
        this.classLoaderFactory = classLoaderFactory;
    }

    public ClassLoader getClassLoaderFor(ClassLoaderSpec spec, List<? extends ClassLoader> parents) {
        if (spec instanceof VisitableURLClassLoader.Spec) {
            VisitableURLClassLoader.Spec clSpec = (VisitableURLClassLoader.Spec) spec;
            if (parents.size() != 1) {
                throw new IllegalStateException("Expected exactly one parent ClassLoader");
            }
            return new MixInClassLoader(parents.get(0), clSpec.getClasspath());
        }
        return classLoaderFactory.getClassLoaderFor(spec, parents);
    }

    private static class MixInClassLoader extends TransformingClassLoader {
        static {
            /*
             * This classloader is thread-safe and TransformingClassLoader is parallel capable,
             * so register as such to reduce contention when running multithreaded builds
            */
            try {
                ClassLoader.registerAsParallelCapable();
            } catch (NoSuchMethodError ignore) {
                // Not using Java 7+, just ignore it
            }
        }

        public MixInClassLoader(ClassLoader parent, List<URL> classPath) {
            super(parent, classPath);
        }

        @Override
        protected byte[] transform(String className, byte[] bytes) {
            // First scan for annotation, and short circuit transformation if not present
            ClassReader classReader = new Java9ClassReader(bytes);

            AnnotationDetector detector = new AnnotationDetector();
            classReader.accept(detector, ClassReader.SKIP_DEBUG | ClassReader.SKIP_CODE);
            if (!detector.found) {
                return bytes;
            }

            if (findLoadedClass(detector.interfaceName) == null) {
                // TODO:ADAM - need to do this earlier
                ClassWriter emptyWriter = new ClassWriter(0);
                emptyWriter.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE, detector.interfaceName.replace('.', '/'), null, Type.getType(Object.class).getInternalName(), null);
                emptyWriter.visitEnd();
                byte[] emptyBytecode = emptyWriter.toByteArray();
                defineClass(detector.interfaceName, emptyBytecode, 0, emptyBytecode.length);
            }

            ClassWriter classWriter = new ClassWriter(0);
            classReader.accept(new TransformingAdapter(classWriter, detector.interfaceName), 0);
            bytes = classWriter.toByteArray();
            return bytes;
        }

        private static class AnnotationDetector extends ClassVisitor {
            private static final String ANNOTATION_DESCRIPTOR = Type.getType(LegacyConsumerInterface.class).getDescriptor();
            String interfaceName;
            private boolean found;

            private AnnotationDetector() {
                super(Opcodes.ASM5);
            }

            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (desc.equals(ANNOTATION_DESCRIPTOR)) {
                    found = true;
                }
                return new AnnotationVisitor(Opcodes.ASM5) {

                    @Override
                    public void visit(String name, Object value) {
                        if (name.equals("value")) {
                            interfaceName = value.toString();
                        }
                    }
                };
            }
        }

        private static class TransformingAdapter extends ClassVisitor {
            private final String mixInInterface;

            public TransformingAdapter(ClassWriter classWriter, String mixInInterface) {
                super(Opcodes.ASM5, classWriter);
                this.mixInInterface = mixInInterface;
            }

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                Set<String> allInterfaces = new LinkedHashSet<String>(Arrays.asList(interfaces));
                allInterfaces.add(mixInInterface.replace('.', '/'));
                super.visit(version, access, name, signature, superName, allInterfaces.toArray(new String[0]));
            }
        }
    }
}
