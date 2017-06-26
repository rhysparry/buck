/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.jvm.java;

import com.facebook.buck.event.api.BuckTracing;
import com.facebook.buck.jvm.java.abi.SourceBasedAbiStubber;
import com.facebook.buck.jvm.java.abi.StubGenerator;
import com.facebook.buck.jvm.java.abi.source.api.BootClasspathOracle;
import com.facebook.buck.jvm.java.abi.source.api.FrontendOnlyJavacTaskProxy;
import com.facebook.buck.jvm.java.plugin.PluginLoader;
import com.facebook.buck.jvm.java.plugin.api.BuckJavacTaskListener;
import com.facebook.buck.jvm.java.plugin.api.BuckJavacTaskProxy;
import com.facebook.buck.jvm.java.plugin.api.PluginClassLoader;
import com.facebook.buck.jvm.java.plugin.api.PluginClassLoaderFactory;
import com.facebook.buck.jvm.java.tracing.JavacPhaseEventLogger;
import com.facebook.buck.jvm.java.tracing.TracingTaskListener;
import com.facebook.buck.jvm.java.tracing.TranslatingJavacPhaseTracer;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.zip.JarBuilder;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.io.PrintWriter; // NOPMD required by API
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.annotation.Nullable;
import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

/** Command used to compile java libraries with a variety of ways to handle dependencies. */
public abstract class Jsr199Javac implements Javac {

  private static final Logger LOG = Logger.get(Jsr199Javac.class);
  private static final JavacVersion VERSION = JavacVersion.of("in memory");

  @Override
  public JavacVersion getVersion() {
    return VERSION;
  }

  @Override
  public String getDescription(
      ImmutableList<String> options,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList) {
    StringBuilder builder = new StringBuilder("javac ");
    Joiner.on(" ").appendTo(builder, options);
    builder.append(" ");
    builder.append("@").append(pathToSrcsList);

    return builder.toString();
  }

  @Override
  public String getShortName() {
    return "javac";
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    throw new UnsupportedOperationException("In memory javac may not be used externally");
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
    throw new UnsupportedOperationException("In memory javac may not be used externally");
  }

  protected abstract JavaCompiler createCompiler(JavacExecutionContext context);

  @Override
  public Invocation newBuildInvocation(
      JavacExecutionContext context,
      BuildTarget invokingRule,
      ImmutableList<String> options,
      ImmutableList<JavacPluginJsr199Fields> pluginFields,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList,
      Optional<Path> workingDirectory,
      JavacCompilationMode compilationMode) {
    return new Invocation() {
      @Override
      public int buildSourceAbiJar(Path sourceAbiJar, Path classUsageFile)
          throws InterruptedException {
        throw new UnsupportedOperationException("To be implemented soon");
      }

      @Override
      public int buildClasses() throws InterruptedException {
        // write javaSourceFilePaths to classes file
        // for buck user to have a list of all .java files to be compiled
        // since we do not print them out to console in case of error
        try {
          context
              .getProjectFilesystem()
              .writeLinesToPath(
                  FluentIterable.from(javaSourceFilePaths)
                      .transform(Object::toString)
                      .transform(ARGFILES_ESCAPER),
                  pathToSrcsList);
        } catch (IOException e) {
          context
              .getEventSink()
              .reportThrowable(
                  e,
                  "Cannot write list of .java files to compile to %s file! Terminating compilation.",
                  pathToSrcsList);
          return 1;
        }

        try (CompilerBundle compilerBundle =
            new CompilerBundle(
                Jsr199Javac.this::createCompiler,
                context,
                invokingRule,
                options,
                pluginFields,
                javaSourceFilePaths,
                compilationMode)) {
          int result = buildWithClasspath(compilerBundle, context, invokingRule, compilationMode);
          if (result != 0 || !context.getDirectToJarOutputSettings().isPresent()) {
            return result;
          }

          return compilerBundle
              .newJarBuilder()
              .createJarFile(
                  Preconditions.checkNotNull(
                      context
                          .getProjectFilesystem()
                          .getPathForRelativePath(
                              context
                                  .getDirectToJarOutputSettings()
                                  .get()
                                  .getDirectToJarOutputPath())));
        } catch (IOException e) {
          LOG.warn(e, "Unable to create jarOutputStream");
        }
        return 1;
      }

      @Override
      public void close() {
        // Nothing
      }
    };
  }

  private int buildWithClasspath(
      CompilerBundle compilerBundle,
      JavacExecutionContext context,
      BuildTarget invokingRule,
      JavacCompilationMode compilationMode) {
    boolean isSuccess = true;
    BuckTracing.setCurrentThreadTracingInterfaceFromJsr199Javac(
        new Jsr199TracingBridge(context.getEventSink(), invokingRule));
    try {
      // Invoke the compilation and inspect the result.
      BuckJavacTaskProxy javacTask = compilerBundle.getJavacTask();

      javacTask.enter();
      if (compilationMode != JavacCompilationMode.ABI) {
        javacTask.generate();
        isSuccess =
            compilerBundle
                    .getDiagnostics()
                    .getDiagnostics()
                    .stream()
                    .filter(diag -> diag.getKind() == Diagnostic.Kind.ERROR)
                    .count()
                == 0;
      }
      DiagnosticCollector<JavaFileObject> diagnostics = compilerBundle.getDiagnostics();
      for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
        LOG.debug("javac: %s", DiagnosticPrettyPrinter.format(diagnostic));
      }

      List<Diagnostic<? extends JavaFileObject>> cleanDiagnostics =
          DiagnosticCleaner.clean(diagnostics.getDiagnostics());

      if (isSuccess) {
        context
            .getUsedClassesFileWriter()
            .writeFile(context.getProjectFilesystem(), context.getCellPathResolver());
        return 0;
      } else {
        if (context.getVerbosity().shouldPrintStandardInformation()) {
          int numErrors = 0;
          int numWarnings = 0;
          for (Diagnostic<? extends JavaFileObject> diagnostic : cleanDiagnostics) {
            Diagnostic.Kind kind = diagnostic.getKind();
            if (kind == Diagnostic.Kind.ERROR) {
              ++numErrors;
            } else if (kind == Diagnostic.Kind.WARNING
                || kind == Diagnostic.Kind.MANDATORY_WARNING) {
              ++numWarnings;
            }

            context.getStdErr().println(DiagnosticPrettyPrinter.format(diagnostic));
          }

          if (numErrors > 0 || numWarnings > 0) {
            context.getStdErr().printf("Errors: %d. Warnings: %d.\n", numErrors, numWarnings);
          }
        }
        return 1;
      }
    } catch (IOException e) {
      LOG.error(e);
      throw new HumanReadableException("IOException during compilation: ", e.getMessage());
    } finally {
      // Clear the tracing interface so we have no chance of leaking it to code that shouldn't
      // be using it.
      BuckTracing.clearCurrentThreadTracingInterfaceFromJsr199Javac();
    }
  }

  private static SourceVersion getTargetVersion(Iterable<String> options) {
    boolean foundTarget = false;
    for (String option : options) {
      if (option.equals("-target")) {
        foundTarget = true;
      } else if (foundTarget) {
        switch (option) {
          case "1.3":
            return SourceVersion.RELEASE_3;
          case "1.4":
            return SourceVersion.RELEASE_4;
          case "1.5":
          case "5":
            return SourceVersion.RELEASE_5;
          case "1.6":
          case "6":
            return SourceVersion.RELEASE_6;
          case "1.7":
          case "7":
            return SourceVersion.RELEASE_7;
          case "1.8":
          case "8":
            return SourceVersion.RELEASE_8;
          default:
            throw new HumanReadableException("target %s not supported", option);
        }
      }
    }

    throw new AssertionError("Unreachable code");
  }

  private static class CompilerBundle implements AutoCloseable {
    private final JavacExecutionContext context;
    private final JavacCompilationMode compilationMode;
    private final JavaCompiler compiler;
    @Nullable private final JavaInMemoryFileManager inMemoryFileManager;
    private final BuckJavacTaskProxy javacTask;
    private final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    private final List<AutoCloseable> closeables = new ArrayList<>();

    public CompilerBundle(
        Function<JavacExecutionContext, JavaCompiler> compilerConstructor,
        JavacExecutionContext context,
        BuildTarget invokingRule,
        ImmutableList<String> options,
        ImmutableList<JavacPluginJsr199Fields> pluginFields,
        ImmutableSortedSet<Path> javaSourceFilePaths,
        JavacCompilationMode compilationMode)
        throws IOException {
      this.context = context;
      this.compilationMode = compilationMode;
      compiler = compilerConstructor.apply(context);
      StandardJavaFileManager standardFileManager =
          compiler.getStandardFileManager(null, null, null);
      addCloseable(standardFileManager);

      StandardJavaFileManager fileManager;
      if (context.getDirectToJarOutputSettings().isPresent()) {
        Path directToJarPath =
            context
                .getProjectFilesystem()
                .getPathForRelativePath(
                    context.getDirectToJarOutputSettings().get().getDirectToJarOutputPath());
        inMemoryFileManager =
            new JavaInMemoryFileManager(
                standardFileManager,
                directToJarPath,
                context.getDirectToJarOutputSettings().get().getClassesToRemoveFromJar());
        addCloseable(inMemoryFileManager);
        fileManager = inMemoryFileManager;
      } else {
        inMemoryFileManager = null;
        fileManager = standardFileManager;
      }

      Iterable<? extends JavaFileObject> compilationUnits;
      try {
        compilationUnits =
            createCompilationUnits(
                fileManager, context.getProjectFilesystem()::resolve, javaSourceFilePaths);
        compilationUnits.forEach(this::addCloseable);
      } catch (IOException e) {
        LOG.warn(e, "Error building compilation units");
        throw e;
      }

      List<String> classNamesForAnnotationProcessing = ImmutableList.of();
      Writer compilerOutputWriter = new PrintWriter(context.getStdErr()); // NOPMD required by API
      PluginClassLoaderFactory loaderFactory =
          PluginLoader.newFactory(context.getClassLoaderCache());

      if (compilationMode != JavacCompilationMode.ABI) {
        javacTask =
            BuckJavacTaskProxy.getTask(
                loaderFactory,
                compiler,
                compilerOutputWriter,
                context.getUsedClassesFileWriter().wrapFileManager(fileManager),
                diagnostics,
                options,
                classNamesForAnnotationProcessing,
                compilationUnits);
      } else {
        javacTask =
            FrontendOnlyJavacTaskProxy.getTask(
                loaderFactory,
                compiler,
                compilerOutputWriter,
                context.getUsedClassesFileWriter().wrapFileManager(fileManager),
                diagnostics,
                options,
                classNamesForAnnotationProcessing,
                compilationUnits);

        javacTask.addPostEnterCallback(
            topLevelTypes -> {
              StubGenerator stubGenerator =
                  new StubGenerator(
                      getTargetVersion(options),
                      javacTask.getElements(),
                      fileManager,
                      context.getEventSink());
              stubGenerator.generate(topLevelTypes);
            });
      }

      PluginClassLoader pluginLoader = loaderFactory.getPluginClassLoader(javacTask);

      BuckJavacTaskListener taskListener = null;
      if (EnumSet.of(
              JavacCompilationMode.FULL_CHECKING_REFERENCES,
              JavacCompilationMode.FULL_ENFORCING_REFERENCES)
          .contains(compilationMode)) {
        taskListener =
            SourceBasedAbiStubber.newValidatingTaskListener(
                pluginLoader,
                javacTask,
                new FileManagerBootClasspathOracle(fileManager),
                compilationMode == JavacCompilationMode.FULL_ENFORCING_REFERENCES
                    ? Diagnostic.Kind.ERROR
                    : Diagnostic.Kind.WARNING);
      }

      TranslatingJavacPhaseTracer tracer =
          new TranslatingJavacPhaseTracer(
              new JavacPhaseEventLogger(invokingRule, context.getEventSink()));
      // TranslatingJavacPhaseTracer is AutoCloseable so that it can detect the end of tracing
      // in some unusual situations
      addCloseable(tracer);

      // Ensure annotation processors are loaded from their own classloader. If we don't do
      // this, then the evidence suggests that they get one polluted with Buck's own classpath,
      // which means that libraries that have dependencies on different versions of Buck's deps
      // may choke with novel errors that don't occur on the command line.
      AnnotationProcessorFactory processorFactory =
          new AnnotationProcessorFactory(
              context.getEventSink(),
              compiler.getClass().getClassLoader(),
              context.getClassLoaderCache(),
              invokingRule);
      addCloseable(processorFactory);

      javacTask.setTaskListener(new TracingTaskListener(tracer, taskListener));
      javacTask.setProcessors(processorFactory.createProcessors(pluginFields));
    }

    private void addCloseable(Object maybeCloseable) {
      if (maybeCloseable instanceof AutoCloseable) {
        closeables.add((AutoCloseable) maybeCloseable);
      }
    }

    public BuckJavacTaskProxy getJavacTask() {
      return javacTask;
    }

    public DiagnosticCollector<JavaFileObject> getDiagnostics() {
      return diagnostics;
    }

    public JarBuilder newJarBuilder() throws IOException {
      JarBuilder jarBuilder = new JarBuilder();
      Preconditions.checkNotNull(inMemoryFileManager).writeToJar(jarBuilder);
      return jarBuilder
          .setObserver(new LoggingJarBuilderObserver(context.getEventSink()))
          .setEntriesToJar(
              context
                  .getDirectToJarOutputSettings()
                  .get()
                  .getEntriesToJar()
                  .stream()
                  .map(context.getProjectFilesystem()::resolve))
          .setMainClass(context.getDirectToJarOutputSettings().get().getMainClass().orElse(null))
          .setManifestFile(
              context.getDirectToJarOutputSettings().get().getManifestFile().orElse(null))
          .setShouldMergeManifests(true)
          .setShouldHashEntries(compilationMode == JavacCompilationMode.ABI)
          .setEntryPatternBlacklist(ImmutableSet.of());
    }

    @Override
    public void close() {
      for (AutoCloseable closeable : Lists.reverse(closeables)) {
        try {
          closeable.close();
        } catch (Exception e) {
          LOG.warn(e, "Unable to close %s; we may be leaking memory.", closeable);
        }
      }
    }

    private Iterable<? extends JavaFileObject> createCompilationUnits(
        StandardJavaFileManager fileManager,
        Function<Path, Path> absolutifier,
        Set<Path> javaSourceFilePaths)
        throws IOException {
      List<JavaFileObject> compilationUnits = new ArrayList<>();
      for (Path path : javaSourceFilePaths) {
        String pathString = path.toString();
        if (pathString.endsWith(".java")) {
          // For an ordinary .java file, create a corresponding JavaFileObject.
          Iterable<? extends JavaFileObject> javaFileObjects =
              fileManager.getJavaFileObjects(absolutifier.apply(path).toFile());
          compilationUnits.add(Iterables.getOnlyElement(javaFileObjects));
        } else if (pathString.endsWith(SRC_ZIP) || pathString.endsWith(SRC_JAR)) {
          // For a Zip of .java files, create a JavaFileObject for each .java entry.
          ZipFile zipFile = new ZipFile(absolutifier.apply(path).toFile());
          boolean hasZipFileBeenUsed = false;
          for (Enumeration<? extends ZipEntry> entries = zipFile.entries();
              entries.hasMoreElements();
              ) {
            ZipEntry entry = entries.nextElement();
            if (!entry.getName().endsWith(".java")) {
              continue;
            }

            hasZipFileBeenUsed = true;
            compilationUnits.add(new ZipEntryJavaFileObject(zipFile, entry));
          }

          if (!hasZipFileBeenUsed) {
            zipFile.close();
          }
        }
      }
      return compilationUnits;
    }
  }

  private static class FileManagerBootClasspathOracle implements BootClasspathOracle {
    private final JavaFileManager fileManager;
    private final Map<String, Set<String>> packagesContents = new HashMap<>();

    private FileManagerBootClasspathOracle(JavaFileManager fileManager) {
      this.fileManager = fileManager;
    }

    @Override
    public boolean isOnBootClasspath(String binaryName) {
      String packageName = getPackageName(binaryName);
      Set<String> packageContents = getPackageContents(packageName);
      return packageContents.contains(binaryName);
    }

    private Set<String> getPackageContents(String packageName) {
      Set<String> packageContents = packagesContents.get(packageName);
      if (packageContents == null) {
        packageContents = new HashSet<>();

        try {
          for (JavaFileObject javaFileObject :
              this.fileManager.list(
                  StandardLocation.PLATFORM_CLASS_PATH,
                  packageName,
                  EnumSet.of(JavaFileObject.Kind.CLASS),
                  true)) {
            packageContents.add(
                fileManager.inferBinaryName(StandardLocation.PLATFORM_CLASS_PATH, javaFileObject));
          }
        } catch (IOException e) {
          throw new HumanReadableException(e, "Failed to list boot classpath contents.");
          // Do nothing
        }
        packagesContents.put(packageName, packageContents);
      }
      return packageContents;
    }

    private String getPackageName(String binaryName) {
      int lastDot = binaryName.lastIndexOf('.');
      if (lastDot < 0) {
        return "";
      }

      return binaryName.substring(0, lastDot);
    }
  }
}
