package io.jenkins.plugins;

import hudson.PluginManager;
import hudson.Util;
import jenkins.model.Jenkins;
import org.apache.commons.io.FileUtils;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.ServletContext;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CustomPluginManager extends PluginManager {
    private static final Logger LOGGER = Logger.getLogger(CustomPluginManager.class.getName());

    public CustomPluginManager(ServletContext context, File rootDir) {
        super(context, new File(rootDir,"plugins"));
    }

    @Override
    protected Collection<String> loadBundledPlugins() throws Exception {
        return null;
    }

    @Nonnull
    @Override
    protected Set<String> loadPluginsFromWar(@Nonnull String fromPath) {
        return super.loadPluginsFromWar(fromPath);
    }

    @Nonnull
    @Override
    protected Set<String> loadPluginsFromWar(@Nonnull String fromPath, @CheckForNull FilenameFilter filter) {
        final Set<String> names = new HashSet<>();

        final ServletContext _context = Jenkins.getInstance().servletContext;
        final Set<String> _plugins = Util.fixNull(_context.getResourcePaths(fromPath));
        final Set<URL> copiedPlugins = new HashSet<>();
        final Set<URL> dependenciesOfNotInstalledPlugins = new HashSet<>();
        final Set<URL> dependenciesOfInstalledPlugins = new HashSet<>();

        for( String pluginPath : _plugins) {
            CopyResult result = copyBundledPluginIfNotInstalled(pluginPath, false, false, names, copiedPlugins);
            if (result == null) {
                // Not a bundled plugin, no need to process dependencies
                continue;
            }
            try {
                addDependencies(result.getUrl(), fromPath, result.isInstalled() ? dependenciesOfInstalledPlugins : dependenciesOfNotInstalledPlugins);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to resolve dependencies for the bundled plugin " + result.getFileName(), e);
            }
        }

        // Process dependencies. These are not detached plugins, but are required by them.
        for (URL dependency : dependenciesOfNotInstalledPlugins) {
            // We do not require installation of such dependencies, but we let Plugin manager to make decisions
            copyBundledPluginIfNotInstalled(dependency, false, true, names, copiedPlugins);
        }
        for (URL dependency : dependenciesOfInstalledPlugins) {
            // Installation of these dependencies is required
            copyBundledPluginIfNotInstalled(dependency, true, true, names, copiedPlugins);
        }

        return names;
    }

    /**
     * Rename a legacy file to a new name, with care to Windows where {@link File#renameTo(File)}
     * doesn't work if the destination already exists.
     */
    private void rename(File legacyFile, File newFile) throws IOException {
        if (!legacyFile.exists())   return;
        if (newFile.exists()) {
            Util.deleteFile(newFile);
        }
        if (!legacyFile.renameTo(newFile)) {
            LOGGER.warning("Failed to rename " + legacyFile + " to " + newFile);
        }
    }

    /**
     * Copies the bundled plugin from the given URL to the destination of the given file name (like 'abc.jpi'),
     * with a reasonable up-to-date check.
     * A convenience method to be used by the {@link #loadBundledPlugins()}.
     * <p>
     * By default, Plugin manager installs a new version only if the version to be copied is newer than the installed one.
     * Particular plugin versions can be enforced via {@link #getEnforcedVersionPluginArtifactIDs()}.
     * In such case the plugin will be always installed.
     * @param src Source URL
     * @param _fileName Name o
     */
    protected void copyBundledPlugin(URL src, final String _fileName) throws IOException {
        String fileName = _fileName.replace(".hpi",".jpi"); // normalize fileNames to have the correct suffix
        String legacyName = fileName.replace(".jpi",".hpi");
        long lastModified = src.openConnection().getLastModified();
        File file = new File(rootDir, fileName);

        // normalization first, if the old file exists.
        rename(new File(rootDir,legacyName),file);

        String pluginArtifactId = fileName.replace(".jpi", "");

        // Copy if there is no file in the destination
        boolean shouldBeCopied = false;
        if (!file.exists()) {
            shouldBeCopied = true;
        } else {
            boolean isVersionEnforced = getEnforcedVersionPluginArtifactIDs().contains(pluginArtifactId);
            if (isVersionEnforced && file.lastModified() != lastModified) {
                LOGGER.log(Level.INFO, "Version of bundled plugin {0} is enforced. The current version will be overriden", pluginArtifactId);
                shouldBeCopied = true;
            }
            if (!isVersionEnforced && file.lastModified() < lastModified) {
                LOGGER.log(Level.INFO, "PluginManager file defines a newer version of the plugin {0}. It will be upgraded", pluginArtifactId);
                shouldBeCopied = true;
            }
        }

        // Copy if the plugin should be copied
        if (shouldBeCopied) {
            FileUtils.copyURLToFile(src, file);
            file.setLastModified(src.openConnection().getLastModified());
        } else {
            LOGGER.log(Level.INFO, "Plugin {0} has been already installed && does not need an upgrade/downgrade. Skipping it", pluginArtifactId);
        }

        // Plugin pinning has been deprecated.
        // See https://groups.google.com/d/msg/jenkinsci-dev/kRobm-cxFw8/6V66uhibAwAJ
    }

    /**
     * Copies a plugin if it has not been installed yet.
     * If the plugin is not required, it will be skipped
     * @param pluginPath Path to the plugin
     * @param copiedPlugins Set of already copied plugins. Can be modified within the method
     * @param names Set of plugin names. Can be modified within the method
     * @param shouldBeBundled Indicates that it is a dependency of the required plugin
     * @return Result if it has been already installed. Null otherwise
     */
    @CheckForNull
    private CopyResult copyBundledPluginIfNotInstalled(String pluginPath, boolean shouldBeBundled, boolean isDependency,
                                                       Set<String> names, Set<URL> copiedPlugins) {
        ServletContext _context = Jenkins.getInstance().servletContext;
        final URL url;
        try {
            url = _context.getResource(pluginPath);
        } catch(MalformedURLException ex) {
            LOGGER.log(Level.WARNING, "Cannot retrieve {0} {1} plugin URL from the plugin path: {2}",
                    new Object[] {shouldBeBundled ? "required" : "optional",
                            isDependency ? "dependency" : "root", pluginPath});
            return null;
        }
        return copyBundledPluginIfNotInstalled(url, shouldBeBundled, isDependency , names, copiedPlugins);
    }

    /**
     * Copies a plugin if it has not been installed yet.
     * If the plugin is not required by Jenkins, the decision regarding installation will be passed
     * to .
     * @param url URL of the plugin
     * @param copiedPlugins Set of already copied plugins. Can be modified within the method
     * @param names Set of plugin names. Can be modified within the method
     * @param shouldBeInstalled Indicates that it is a dependency of the required plugin
     * @return Result if it has been installed to Jenkins or processed in different way.
     *         {@code null} otherwise.
     */
    @CheckForNull
    private CopyResult copyBundledPluginIfNotInstalled(URL url, boolean shouldBeInstalled, boolean isDependency,
                                                       Set<String> names, Set<URL> copiedPlugins) {

        final String pluginType = isDependency ? "dependency" : (shouldBeInstalled ? "required" : "optional");

        String fileName = new File(url.getFile()).getName();
        if(fileName.length()==0) {
            // see http://www.nabble.com/404-Not-Found-error-when-clicking-on-help-td24508544.html
            // I suspect some containers are returning directory names.
            LOGGER.log(Level.WARNING, "Got empty plugin file name from the plugin path: {0}. Cannot install", url);
            return null;
        }
        String pluginArtifactId = fileName.replace(".hpi", "").replace(".jpi", "");

        // Check if it should be copied
        if (copiedPlugins.contains(url)) {
            // Ignore. Already copied.
            LOGGER.log(Level.INFO, "Skipping installation of {0} plugin {1}, because it has been already installed",
                    new Object[] {pluginType, pluginArtifactId});
            return null;
        }
        final Set<String> requiredPluginArtifactIDs = getRequiredPluginArtifactIDs();
        if (!requiredPluginArtifactIDs.contains(pluginArtifactId) && !shouldBeInstalled) {
            // Is not a plugin, which is required for the installation.
            // Let Plugin manager to make a decision regarding it.
            final CopyResult notRequiredInstallResult = handleNotRequiredBundledPlugin(url, fileName, pluginType);
            if (notRequiredInstallResult != null) {
                // Plugin manager has processed the plugin, no need to do anything
                return notRequiredInstallResult;
            }
        }

        // Finally install the bundled plugin
        LOGGER.log(Level.INFO, "Installing the bundled {1} plugin {0} to Jenkins",
                new Object[] {pluginArtifactId, pluginType});

        try {
            names.add(fileName);
            copyBundledPlugin(url, fileName);
            copiedPlugins.add(url);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to extract the bundled " + pluginType + " plugin " + fileName, e);
            return null;
        }
        return new CopyResult(url, fileName, true);
    }

    /**
     * Performs handling of the bundled plugin, which installation is not required.
     * Extensions can alter the behavior for such plugins.
     * @param url URL of the plugin file to be handled.
     * @param fileName Plugin file name
     * @param pluginType Plugin type String
     * @return Result of the custom plugin handling.
     *         {@code null} If the plugin should be installed
     * @since TODO
     */
    @CheckForNull
    protected CopyResult handleNotRequiredBundledPlugin(@Nonnull URL url, @Nonnull String fileName,
                                                        @Nonnull String pluginType) {
        // Default behavior - skip the plugin installation
        String pluginArtifactId = fileName.replace(".hpi", "").replace(".jpi", "");
        LOGGER.log(Level.INFO, "Skipping installation of the {0} bundled {1} plugin, because it is not a required plugin",
                new Object[] {pluginType, pluginArtifactId});
        return new CopyResult(url, fileName, false);
    }

    /**
     * Gets set of plugins, which must be installed during the Jenkins instance startup.
     * @return Set of plugin artifact IDs,
     * @since TODO
     */
    @Nonnull
    public Set<String> getRequiredPluginArtifactIDs() {
        return Collections.emptySet();
    }

    /**
     * Gets set of plugins, which must have a version provided by the plugin Manager.
     * @return Set of plugin artifact IDs,
     * @since TODO
     */
    @Nonnull
    public Set<String> getEnforcedVersionPluginArtifactIDs() {
        return Collections.emptySet();
    }

    /**
     * Stores info about copy operations.
     * @since TODO
     */
    protected static class CopyResult {
        final URL url;
        final String fileName;
        final boolean installed;

        public CopyResult(@Nonnull URL url, @Nonnull String fileName, boolean isInstalled) {
            this.url = url;
            this.fileName = fileName;
            this.installed = isInstalled;
        }

        /**
         * Name of the plugin file, which has been copied.
         * @return Name of the plugin file
         */
        @Nonnull
        public String getFileName() {
            return fileName;
        }

        /**
         * Indicates that Plugin manager has installed this plugin to Jenkins.
         * @return {@code true} if the plugin has been installed to Jenkins.
         *         {@code false} means that the plugin installation has been handled in a different way.
         */
        public boolean isInstalled() {
            return installed;
        }

        /**
         * Gets URL of the plugin, which has been installed.
         * @return Plugin {@link URL}
         */
        @Nonnull
        public URL getUrl() {
            return url;
        }
    }
}
