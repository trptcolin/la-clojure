package org.jetbrains.plugins.clojure.runner;

import com.intellij.execution.CantRunException;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.*;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdkType;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.JDOMExternalizer;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: peter
 * Date: Jan 7, 2009
 * Time: 6:04:34 PM
 * Copyright 2007, 2008, 2009 Red Shark Technology
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
public class ClojureScriptRunConfiguration extends ModuleBasedConfiguration {
    private ClojureScriptConfigurationFactory factory;
    private String scriptPath;
    private String workDir;
    private String vmParams;
    private String scriptParams;
    private boolean isDebugEnabled;

    @NonNls
    private static final String CLOJURE_STARTER = "clojure.lang.Repl";

    public ClojureScriptRunConfiguration(ClojureScriptConfigurationFactory factory, Project project, String name) {
        super(name, new RunConfigurationModule(project), factory);
        this.factory = factory;
    }

    public Collection<Module> getValidModules() {
        Module[] modules = ModuleManager.getInstance(getProject()).getModules();
        ArrayList<Module> res = new ArrayList<Module>();
        for (Module module : modules) {
            res.add(module);
        }
        return res;
    }

    public void setWorkDir(String dir) {
        workDir = dir;
    }

    public String getWorkDir() {
        return workDir;
    }

    public String getAbsoluteWorkDir() {
        if (!new File(workDir).isAbsolute()) {
            return new File(getProject().getLocation(), workDir).getAbsolutePath();
        }
        return workDir;
    }

    public void readExternal(Element element) throws InvalidDataException {
        super.readExternal(element);
        readModule(element);
        scriptPath = JDOMExternalizer.readString(element, "path");
        vmParams = JDOMExternalizer.readString(element, "vmparams");
        scriptParams = JDOMExternalizer.readString(element, "params");
        workDir = JDOMExternalizer.readString(element, "workDir");
        isDebugEnabled = Boolean.parseBoolean(JDOMExternalizer.readString(element, "debug"));
        workDir = getWorkDir();
    }

    public void writeExternal(Element element) throws WriteExternalException {
        super.writeExternal(element);
        writeModule(element);
        JDOMExternalizer.write(element, "path", scriptPath);
        JDOMExternalizer.write(element, "vmparams", vmParams);
        JDOMExternalizer.write(element, "params", scriptParams);
        JDOMExternalizer.write(element, "workDir", workDir);
        JDOMExternalizer.write(element, "debug", isDebugEnabled);
    }

    protected ModuleBasedConfiguration createInstance() {
        return new ClojureScriptRunConfiguration(factory, getConfigurationModule().getProject(), getName());
    }

    public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new ClojureRunConfigurationEditor();
    }

    public static void configureScriptSystemClassPath(final JavaParameters params, final Module module) throws CantRunException {
        params.configureByModule(module, JavaParameters.JDK_ONLY);
        //params.getClassPath().add(CLOJURE_SDK);
        params.configureByModule(module, JavaParameters.JDK_AND_CLASSES);
    }

    private void configureJavaParams(JavaParameters params, Module module) throws CantRunException {

        // Setting up classpath
        configureScriptSystemClassPath(params, module);

        params.setWorkingDirectory(getAbsoluteWorkDir());

        // add user parameters
        params.getVMParametersList().addParametersString(vmParams);

        // set starter class
        params.setMainClass(CLOJURE_STARTER);
    }

    private boolean isJarFromJRE(String path, Module module) {
        if (path == null) return false;
        OrderEntry[] entries = ModuleRootManager.getInstance(module).getOrderEntries();
        for (OrderEntry entry : entries) {
            if (entry instanceof JdkOrderEntry) {
                JdkOrderEntry jdkEntry = (JdkOrderEntry) entry;
                for (VirtualFile file : jdkEntry.getFiles(OrderRootType.CLASSES)) {
                    if (file.getPresentableUrl().equals(path)) return true;
                }
            }
        }
        return false;
    }

    public StringBuffer getClearClassPathString(JavaParameters params, final Module module) {
        List<String> list = params.getClassPath().getPathList();
        Sdk jdk = params.getJdk();
        StringBuffer buffer = new StringBuffer();
        if (jdk != null) {
            for (String libPath : list) {
                if (!isJarFromJRE(libPath, module) /*&& !isJarFromClojureLib(libPath, module)*/) {
                    buffer.append(libPath).append(File.pathSeparator);
                }
            }
        }
        //buffer.append(CLOJURE_SDK);
        return buffer;
    }

    private void configureScript(JavaParameters params) {
        // add script
        params.getProgramParametersList().add(scriptPath);

        // add script parameters
        params.getProgramParametersList().addParametersString(scriptParams);
    }

    public Module getModule() {
        return getConfigurationModule().getModule();
    }

    public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment environment) throws ExecutionException {
        final Module module = getModule();
        if (module == null) {
            throw new ExecutionException("Module is not specified");
        }

        final ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
        final Sdk sdk = rootManager.getSdk();
        if (sdk == null || !(sdk.getSdkType() instanceof JavaSdkType)) {
            throw CantRunException.noJdkForModule(getModule());
        }

        final VirtualFile script = findScriptFile();

        final JavaCommandLineState state = new JavaCommandLineState(environment) {
            protected JavaParameters createJavaParameters() throws ExecutionException {
                JavaParameters params = new JavaParameters();

                configureJavaParams(params, module);
                configureScript(params);

                return params;
            }
        };

        state.setConsoleBuilder(TextConsoleBuilderFactory.getInstance().createBuilder(getProject()));
        return state;

    }

    @Nullable
    private VirtualFile findScriptFile() {
        return VirtualFileManager.getInstance().findFileByUrl("file://" + scriptPath);
    }

    public void setScriptPath(String path) {
        this.scriptPath = path;
    }

    public String getScriptPath() {
        return scriptPath;
    }

    public String getVmParams() {
        return vmParams;
    }

    public String getScriptParams() {
        return scriptParams;
    }

    public void setVmParams(String params) {
        vmParams = params;
    }

    public void setIsDebugEnabled(boolean isEnabled) {
        isDebugEnabled = isEnabled;
    }

    public void setScriptParams(String params) {
        scriptParams = params;
    }

    public boolean getIsDebugEnabled() {
        return isDebugEnabled;
    }
}