/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.j2objc.pipeline;

import com.google.common.base.Preconditions;
import com.google.devtools.j2objc.Options;
import com.google.devtools.j2objc.ast.CompilationUnit;
import com.google.devtools.j2objc.file.InputFile;
import com.google.devtools.j2objc.util.ErrorUtil;
import com.google.devtools.j2objc.util.FileUtil;
import com.google.devtools.j2objc.util.Parser;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.logging.Logger;

/**
 * Class for processing GenerationUnits in minimum increments of one GenerationUnit.
 *
 * @author Tom Ball, Keith Stanger, Mike Thvedt
 */
abstract class FileProcessor {

  private static final Logger logger = Logger.getLogger(FileProcessor.class.getName());

  private final Parser parser;
  protected final BuildClosureQueue closureQueue;
  protected final Options options;

  public FileProcessor(Parser parser) {
    this.parser = Preconditions.checkNotNull(parser);
    this.options = parser.options();
    if (options.buildClosure()) {
      // Should be an error if the user specifies this with --build-closure
      assert !options.getHeaderMap().useSourceDirectories();
      closureQueue = new BuildClosureQueue(options);
    } else {
      closureQueue = null;
    }
  }

  public void processInputs(Iterable<ProcessingContext> inputs) {
    for (ProcessingContext input : inputs) {
      processInput(input);
    }
  }

  public void processBuildClosureDependencies() {
    if (closureQueue != null) {
      while (true) {
        InputFile file = closureQueue.getNextFile();
        if (file == null) {
          file = closureQueue.getNextFile();
        }
        if (file == null) {
          break;
        }
        processInput(ProcessingContext.fromFile(file, options));
      }
    }
  }

  private void processInput(ProcessingContext input) {
    try {
      InputFile file = input.getFile();
      logger.finest("parsing " + file);

      CompilationUnit compilationUnit = parser.parse(file);
      if (compilationUnit == null) {
        handleError(input);
        return;
      }

      processCompiledSource(input, compilationUnit);
    } catch (RuntimeException | Error e) {
      ErrorUtil.fatalError(e, input.getOriginalSourcePath());
    }
  }

  private void processCompiledSource(ProcessingContext input,
      com.google.devtools.j2objc.ast.CompilationUnit unit) {
    InputFile file = input.getFile();
    if (closureQueue != null) {
      closureQueue.addProcessedName(FileUtil.getQualifiedMainTypeName(file, unit));
    }
    try {
      processConvertedTree(input, unit);
    } catch (Throwable t) {
      // Report any uncaught exceptions.
      ErrorUtil.fatalError(t, input.getOriginalSourcePath());
    }
  }

  protected abstract void processConvertedTree(
      ProcessingContext input, com.google.devtools.j2objc.ast.CompilationUnit unit);

  protected abstract void handleError(ProcessingContext input);

  /**
   * Maps processing contexts using their canonical paths. This allows a
   * front-end to refer to a source file using a different but equivalent
   * path, without changing what path was specified.
   */
  @SuppressWarnings("serial")
  private static class CanonicalPathMap extends HashMap<String, ProcessingContext> {

    public CanonicalPathMap(int initialSize) {
      super(initialSize);
    }

    @Override
    public ProcessingContext get(Object key) {
      return super.get(canonicalizePath((String) key));
    }

    @Override
    public ProcessingContext put(String key, ProcessingContext value) {
      return super.put(canonicalizePath((String) key), value);
    }

    private String canonicalizePath(String path) {
      try {
        return new File(path).getCanonicalPath();
      } catch (IOException e) {
        // Shouldn't happen, but returning the unchanged path is safe.
        return path;
      }
    }
  }
}
