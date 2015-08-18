/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.plugin.impl;

import io.gravitee.plugin.*;
import io.gravitee.plugin.utils.FileUtils;
import io.gravitee.plugin.utils.GlobMatchingFileVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.ClassUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * @author David BRASSELY (brasseld at gmail.com)
 */
public class PluginManagerImpl implements PluginManager {

    protected final Logger LOGGER = LoggerFactory.getLogger(PluginManagerImpl.class);

    private final static String JAR_EXTENSION = ".jar";
    private final static String JAR_GLOB = '*' + JAR_EXTENSION;

    private final static String PLUGIN_DESCRIPTOR_PROPERTIES_FILE = "plugin.properties";

    private boolean initialized = false;

    @Value("${plugin.workspace}")
    private String workspacePath;

    @Autowired
    private ClassLoaderFactory classLoaderFactory;

    @Autowired
    private Collection<PluginHandler> pluginHandlers;

    private final Map<String, Plugin> plugins = new HashMap<>();

    /**
     * Empty constructor is used to defined workspace directory from @Value annotation on workspacePath field.
     */
    public PluginManagerImpl() {

    }

    public PluginManagerImpl(String workspacePath) {
        this.workspacePath = workspacePath;
    }

    /*
    @Override
    protected void doStart() throws Exception {
        super.doStart();

        initialize();
    }
    */

    public void initialize() {
        if (! initialized) {
            LOGGER.info("Initializing plugin workspace.");
            this.initializeFromWorkspace();
            LOGGER.info("Initializing plugin workspace. DONE");
        } else {
            LOGGER.warn("Plugin workspace has already been initialized.");
        }
    }

    @Override
    public Collection<Plugin> getPlugins() {
        return plugins.values();
    }

    public void initializeFromWorkspace() {
        if (workspacePath == null || workspacePath.isEmpty()) {
            LOGGER.error("Plugin workspace directory is not specified.");
            throw new RuntimeException("Plugin workspace directory is not specified.");
        }

        File workspaceDir = new File(workspacePath);

        // quick sanity check on the install root
        if (! workspaceDir.isDirectory()) {
            LOGGER.error("Invalid workspace directory, {} is not a directory.", workspaceDir.getAbsolutePath());
            throw new RuntimeException("Invalid workspace directory. Not a directory: "
                    + workspaceDir.getAbsolutePath());
        }

        LOGGER.info("Loading plugins from {}", workspaceDir.getAbsoluteFile());
        List<File> subdirectories = getChildren(workspaceDir.getAbsolutePath());

        LOGGER.info("\t{} plugin directories have been found.", subdirectories.size());
        for(File pluginDir: subdirectories) {
            loadPlugin(pluginDir.getAbsolutePath());
        }

        initialized = true;
    }

    /**
     * Load a plugin from file system.
     *
     * Plugin structure in the workspace is as follow:
     *  my-plugin-dir/
     *      my-plugin.jar
     *      lib/
     *          dependency01.jar
     *          dependency02.jar
     *
     * @param pluginDir The directory containing the plugin definition
     */
    private void loadPlugin(String pluginDir) {
        Path pluginDirPath = FileSystems.getDefault().getPath(pluginDir);
        LOGGER.info("Trying to load plugin from {}", pluginDirPath);

        PluginManifest descriptor = readPluginDescriptor(pluginDirPath);
        if (descriptor != null) {
            preparePluginClassLoader(pluginDirPath, descriptor);
            Plugin plugin = createPlugin(descriptor);
            boolean registered = registerPlugin(plugin);

            if (!registered) {
                classLoaderFactory.removePluginClassLoader(descriptor.id());
            }
        }
    }

    private Plugin createPlugin(PluginManifest pluginManifest) {
        try {
            final Class<?> pluginClass =
                ClassUtils.forName(pluginManifest.plugin(),
                        classLoaderFactory.getPluginClassLoader(pluginManifest.id()));

            return new PluginImpl(pluginManifest.id(), pluginClass);
        } catch (ClassNotFoundException cnfe) {
            LOGGER.error("Unable to get plugin class with name {}", pluginManifest.plugin());
            throw new IllegalArgumentException("Unable to get plugin class with name " + pluginManifest.plugin(), cnfe);
        }
    }

    private boolean registerPlugin(Plugin plugin) {
        plugins.putIfAbsent(plugin.id(), plugin);

        for (PluginHandler pluginHandler : pluginHandlers) {
            LOGGER.debug("Trying to handle plugin {} with {}", plugin.id(), pluginHandler.getClass().getName());
            if (pluginHandler.canHandle(plugin)) {
                pluginHandler.handle(plugin);
                LOGGER.info("Plugin {} handled by {}", plugin.id(), pluginHandler.getClass().getName());
                return true;
            }
        }

        LOGGER.warn("No Plugin handler found for {} [{}]", plugin.id(), plugin.clazz().getName());

        return false;
    }

    /**
     *
     * @param pluginPath
     * @return
     */
    private PluginManifest readPluginDescriptor(Path pluginPath) {
        try {
            Iterator iterator = FileUtils.newDirectoryStream(pluginPath, JAR_GLOB).iterator();
            if (! iterator.hasNext()) {
                LOGGER.debug("Unable to found a jar in the root directory: {}", pluginPath);
                return null;
            }

            Path pluginJarPath = (Path) iterator.next();
            LOGGER.debug("Found a jar in the root directory, looking for a plugin descriptor in {}", pluginJarPath);

            Properties pluginDescriptorProperties = loadPluginDescriptor(pluginJarPath.toString());
            if (pluginDescriptorProperties == null) {
                LOGGER.error("No plugin.properties can be found from {}", pluginJarPath);
                return null;
            }

            LOGGER.info("A plugin descriptor has been loaded from: {}", pluginJarPath);

            PluginManifestValidator validator = new PropertiesBasedPluginManifestValidator(pluginDescriptorProperties);
            if (! validator.validate()) {
                LOGGER.error("Plugin descriptor not valid, skipping plugin registration.");
                return null;
            }

            return create(pluginDescriptorProperties);
        } catch (IOException ioe) {
            LOGGER.error("Unexpected error while trying to load plugin descriptor", ioe);
            throw new IllegalStateException("Unexpected error while trying to load plugin descriptor", ioe);
        }
    }

    private void preparePluginClassLoader(Path pluginDirPath, PluginManifest pluginManifest) {
        // Prepare plugin classloader by reading *.jar
        try {
            GlobMatchingFileVisitor visitor = new GlobMatchingFileVisitor(JAR_GLOB);
            Files.walkFileTree(pluginDirPath, visitor);
            List<Path> pluginDependencies = visitor.getMatchedPaths();
            URL[] dependencies = listToArray(pluginDependencies);
            classLoaderFactory.createPluginClassLoader(pluginManifest.id(), dependencies);
        } catch (IOException ioe) {
            LOGGER.error("Unexpected error while looking for plugin dependencies", ioe);
        }
    }

    private PluginManifest create(Properties properties) {
        final String id = properties.getProperty(PluginManifestProperties.MANIFEST_ID_PROPERTY);
        final String description = properties.getProperty(PluginManifestProperties.MANIFEST_DESCRIPTION_PROPERTY);
        final String clazz = properties.getProperty(PluginManifestProperties.MANIFEST_CLASS_PROPERTY);
        final String name = properties.getProperty(PluginManifestProperties.MANIFEST_NAME_PROPERTY);
        final String version = properties.getProperty(PluginManifestProperties.MANIFEST_VERSION_PROPERTY);

        return new PluginManifest() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public String version() {
                return version;
            }

            @Override
            public String plugin() {
                return clazz;
            }
        };
    }

    private Properties loadPluginDescriptor(String pluginPath) {
        try (FileSystem zipFileSystem = FileUtils.createZipFileSystem(pluginPath, false)){
            final Path root = zipFileSystem.getPath("/");

            // Walk the jar file tree and search for plugin.properties file
            PluginDescriptorVisitor visitor = new PluginDescriptorVisitor();
            Files.walkFileTree(root, visitor);
            Path pluginDescriptorPath = visitor.getPluginDescriptor();

            if (pluginDescriptorPath != null) {
                Properties properties = new Properties();
                properties.load(Files.newInputStream(pluginDescriptorPath));

                return properties;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    private List<File> getChildren(String directory) {
        DirectoryStream.Filter<Path> filter = file -> (Files.isDirectory(file));

        List<File> files = new ArrayList<>();
        Path dir = FileSystems.getDefault().getPath(directory);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir,
                filter)) {
            for (Path path : stream) {
                files.add(path.toFile());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return files;
    }

    private URL[] listToArray(List<Path> paths) {
        URL [] urls = new URL[paths.size()];
        int idx = 0;

        for(Path path: paths) {
            try {
                urls[idx++] = path.toUri().toURL();
            } catch (IOException ioe) {}
        }

        return urls;
    }

    public void setClassLoaderFactory(ClassLoaderFactory classLoaderFactory) {
        this.classLoaderFactory = classLoaderFactory;
    }

    public void setPluginHandlers(Collection<PluginHandler> pluginHandlers) {
        this.pluginHandlers = pluginHandlers;
    }

    class PluginDescriptorVisitor extends SimpleFileVisitor<Path> {
        private Path pluginDescriptor = null;

        @Override
        public FileVisitResult visitFile(Path file,
                                         BasicFileAttributes attrs) throws IOException {
            if (file.getFileName().toString().equals(PLUGIN_DESCRIPTOR_PROPERTIES_FILE)) {
                pluginDescriptor = file;
                return FileVisitResult.TERMINATE;
            }

            return super.visitFile(file, attrs);
        }

        public Path getPluginDescriptor() {
            return pluginDescriptor;
        }
    }
}
