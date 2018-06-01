/**
 * Copyright (c) 2018 SUSE LLC
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package com.suse.manager.webui.services;

import static com.suse.manager.webui.services.SaltConstants.SALT_FS_PREFIX;
import static com.suse.manager.webui.services.SaltConstants.SUMA_STATE_FILES_ROOT_PATH;
import static com.suse.manager.webui.services.SaltServerActionService.PACKAGES_PKGINSTALL;
import static com.suse.manager.webui.services.SaltServerActionService.PACKAGES_PATCHINSTALL;
import static com.suse.manager.webui.services.SaltServerActionService.PARAM_UPDATE_STACK_PATCHES;
import static com.suse.manager.webui.services.SaltServerActionService.PARAM_REGULAR_PATCHES;
import static com.suse.manager.webui.services.impl.SaltSSHService.ACTION_STATES_LIST;
import static com.suse.manager.webui.services.impl.SaltSSHService.DEFAULT_TOPS;

import com.redhat.rhn.domain.action.ActionChain;
import com.redhat.rhn.domain.server.MinionServer;
import com.redhat.rhn.domain.server.MinionServerFactory;

import com.suse.manager.utils.MinionServerUtils;
import com.suse.manager.webui.utils.AbstractSaltRequisites;
import com.suse.manager.webui.utils.ActionSaltState;
import com.suse.manager.webui.utils.IdentifiableSaltState;
import com.suse.manager.webui.utils.SaltModuleRun;
import com.suse.manager.webui.utils.SaltPkgInstalled;
import com.suse.manager.webui.utils.SaltPatchInstalled;

import com.suse.manager.webui.utils.SaltState;
import com.suse.manager.webui.utils.SaltStateGenerator;
import com.suse.manager.webui.utils.SaltSystemReboot;
import com.suse.manager.webui.utils.SaltTop;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.log4j.Logger;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.DirectoryStream;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/**
 * Service to manage the Salt Action Chains generated by Suse Manager.
 */
public class SaltActionChainGeneratorService {

    /** Logger */
    private static final Logger LOG = Logger.getLogger(SaltActionChainGeneratorService.class);

    // Singleton instance of this class
    public static final SaltActionChainGeneratorService INSTANCE = new SaltActionChainGeneratorService();

    public static final String ACTION_STATE_ID_PREFIX = "mgr_actionchain_";
    public static final String ACTION_STATE_ID_ACTION_PREFIX = "_action_";
    public static final String ACTION_STATE_ID_CHUNK_PREFIX = "_chunk_";
    public static final String ACTIONCHAIN_SLS_FOLDER = "actionchains";

    private static final String ACTIONCHAIN_SLS_FILE_PREFIX = "actionchain_";

    public static final Pattern ACTION_STATE_PATTERN =
            Pattern.compile(".*\\|-" + ACTION_STATE_ID_PREFIX + "(\\d+)" +
                    ACTION_STATE_ID_ACTION_PREFIX + "(\\d+)" +
                    ACTION_STATE_ID_CHUNK_PREFIX + "(\\d+).*");

    private static final Pattern SALT_FILE_REF =
            Pattern.compile("(" + SALT_FS_PREFIX + "|topfn:\\s*)([a-zA-Z0-9_\\./]+)");

    private Path suseManagerStatesFilesRoot;
    private boolean skipSetOwner;

    /**
     * Default constructor.
     */
    public SaltActionChainGeneratorService() {
        suseManagerStatesFilesRoot = Paths.get(SUMA_STATE_FILES_ROOT_PATH);
    }

    /**
     * Get the number of chunks for each minion.
     * @param minionStates the states for each minion
     * @return a map with the number of chunks for each minion
     */
    public Map<MinionServer, Integer> getChunksPerMinion(Map<MinionServer, List<SaltState>> minionStates) {
        return minionStates.entrySet().stream().collect(
                        Collectors.toMap(
                                entry -> entry.getKey(),
                                entry -> entry.getValue().stream()
                                        .mapToInt(state -> mustSplit(state, entry.getKey()) ? 1 : 0).sum() + 1));
    }

    /**
     * Generates SLS files for an Action Chain.
     * @param actionChain the chain
     * @param minion a minion to execute the chain on
     * @param states a list of states
     * @param sshExtraFileRefs extra files to be added to the state tarball by salt-ssh. Will
     *                         be stored on the minion to be available for subsequent calls.
     * @return map containing minions and the corresponding number of generated chunks
     */
    public Map<MinionServer, Integer> createActionChainSLSFiles(ActionChain actionChain, MinionServer minion,
                                                                List<SaltState> states,
                                                                Optional<String> sshExtraFileRefs) {
        int chunk = 1;
        List<SaltState> fileStates = new LinkedList<>();
        for (int i = 0; i < states.size(); i++) {
            SaltState state = states.get(i);

            if (state instanceof AbstractSaltRequisites) {
                prevRequisiteRef(fileStates).ifPresent(ref -> {
                    ((AbstractSaltRequisites)state).addRequire(ref.getKey(), ref.getValue());
                });
            }
            if (state instanceof IdentifiableSaltState) {
                IdentifiableSaltState modRun = (IdentifiableSaltState)state;
                modRun.setId(modRun.getId() + ACTION_STATE_ID_CHUNK_PREFIX + chunk);
            }
            Optional<Long> nextActionId = nextActionId(states, i);

            if (mustSplit(state, minion)) {
                if (isSaltUpgrade(state)) {
                    fileStates.add(
                            endChunk(actionChain, chunk, nextActionId,
                                    prevRequisiteRef(fileStates), sshExtraFileRefs));
                    fileStates.add(state);
                    fileStates.add(stopIfPreviousFailed(prevRequisiteRef(fileStates)));
                    saveChunkSLS(fileStates, minion, actionChain.getId(), chunk);
                    fileStates.clear();
                    chunk++;
                    fileStates.add(checkSaltUpgradeChunk(state));
                }
                else {
                    fileStates.add(state);
                    if (i < states.size() - 1) {
                        fileStates.add(
                                endChunk(actionChain, chunk, nextActionId,
                                        prevRequisiteRef(fileStates), sshExtraFileRefs));
                    }

                    saveChunkSLS(fileStates, minion, actionChain.getId(), chunk);
                    chunk++;
                    fileStates.clear();
                }
            }
            else {
                fileStates.add(state);
            }
        }
        if (!fileStates.isEmpty()) {
            saveChunkSLS(fileStates, minion, actionChain.getId(), chunk);
        }

        return Collections.singletonMap(minion, chunk);
    }

    private Optional<Long> nextActionId(List<SaltState> states, int currentPos) {
        SaltState state = states.size() > currentPos + 1 ? states.get(currentPos + 1) : null;
        if (state instanceof ActionSaltState) {
            ActionSaltState actionState = (ActionSaltState)state;
            return Optional.of(actionState.getActionId());
        }
        return Optional.empty();
    }

    private SaltState endChunk(ActionChain actionChain, int chunk, Optional<Long> nextActionId,
                               Optional<Pair<String, String>> lastRef, Optional<String> sshExtraFileRefs) {
        Map<String, Object> args = new LinkedHashMap<>(2);
        args.put("actionchain_id", actionChain.getId());
        args.put("chunk", chunk + 1);
        nextActionId.ifPresent(actionId ->
                args.put("next_action_id", actionId));
        sshExtraFileRefs.ifPresent(refs ->
                args.put("ssh_extra_filerefs", refs));
        SaltModuleRun modRun = new SaltModuleRun("schedule_next_chunk", "mgractionchains.next", args);
        lastRef.ifPresent(ref -> modRun.addRequire(ref.getKey(), ref.getValue()));
        return modRun;
    }

    private SaltState stopIfPreviousFailed(Optional<Pair<String, String>> lastRef) {
        Map<String, Object> args = new LinkedHashMap<>(1);
        Map<String, String> onFailedEntry = new LinkedHashMap<>(1);
        List<Object> onFailedList = new ArrayList<>();
        lastRef.ifPresent(ref -> {
            onFailedEntry.put(ref.getKey(), ref.getValue());
            onFailedList.add(onFailedEntry);
            args.put("onfail", onFailedList);
        });
        SaltModuleRun modRun =
                new SaltModuleRun("clean_action_chain_if_previous_failed",
                        "mgractionchains.clean", args);
        return modRun;
    }

    private SaltState checkSaltUpgradeChunk(SaltState state) {
        SaltModuleRun moduleRun = (SaltModuleRun) state;
        Optional<String> mods = getModsString(moduleRun);
        SaltState retState = null;

        if (mods.isPresent() && mods.get().contains(PACKAGES_PKGINSTALL)) {
            Map<String, Map<String, String>> paramPillar =
                    (Map<String, Map<String, String>>) moduleRun.getKwargs().get("pillar");
            SaltPkgInstalled pkgInstalled = new SaltPkgInstalled();
            for (Map.Entry<String, String> entry : paramPillar.get("param_pkgs").entrySet()) {
                pkgInstalled.addPackage(entry.getKey(), entry.getValue());
            }
            retState = pkgInstalled;
        }
        else if (mods.isPresent() && mods.get().contains(PACKAGES_PATCHINSTALL)) {
            Map<String, List<String>> paramPillar =
                    (Map<String, List<String>>) moduleRun.getKwargs().get("pillar");
            SaltPatchInstalled patchInstalled = new SaltPatchInstalled();
            for (String patch : paramPillar.get(PARAM_UPDATE_STACK_PATCHES)) {
                patchInstalled.addPatch(patch);
            }
            for (String patch : paramPillar.get(PARAM_REGULAR_PATCHES)) {
                patchInstalled.addPatch(patch);
            }
            retState = patchInstalled;
        }
        return retState;
    }

    /**
     * Get a requisite reference to the last state in the list.
     * @param fileStates salt state list
     * @return a requisite reference
     */
    private Optional<Pair<String, String>> prevRequisiteRef(List<SaltState> fileStates) {
        if (fileStates.size() > 0) {
            SaltState previousState = fileStates.get(fileStates.size() - 1);
            return previousState.getData().entrySet().stream().findFirst()
                .map(entry -> ((Map<String, ?>)entry.getValue()).entrySet().stream()
                    .findFirst().map(ent -> {
                        String[] stateMod = ent.getKey().split("\\.");
                        if (stateMod.length == 2) {
                            return stateMod[0];
                        }
                        else {
                            throw new RuntimeException("Could not get Salt requisite reference for " + ent.getKey());
                        }
                    })
                    .map(mod -> new ImmutablePair<>(mod, entry.getKey()))
                    .orElseThrow(() ->
                         new RuntimeException("Could not get Salt requisite reference for " + entry.getKey())));
        }
        return Optional.empty();
    }

    private boolean isSaltUpgrade(SaltState state) {
        if (state instanceof SaltModuleRun) {
            SaltModuleRun moduleRun = (SaltModuleRun)state;

            Optional<String> mods = getModsString(moduleRun);

            if (mods.isPresent() && mods.get().contains(PACKAGES_PKGINSTALL)) {
                if (moduleRun.getKwargs() != null) {
                    Map<String, Map<String, String>> paramPillar =
                            (Map<String, Map<String, String>>) moduleRun.getKwargs().get("pillar");
                    if (!paramPillar.get("param_pkgs").entrySet().stream()
                            .filter(entry -> entry.getKey().equals("salt"))
                            .map(entry -> entry.getKey())
                            .collect(Collectors.toList()).isEmpty()) {
                        return true;
                    }
                }
            }
            else if (mods.isPresent() && mods.get().contains(PACKAGES_PATCHINSTALL)) {
                if (moduleRun.getKwargs() != null) {
                    Map<String, List<String>> paramPillar =
                            (Map<String, List<String>>) moduleRun.getKwargs().get("pillar");
                    if (paramPillar.containsKey("include_salt_upgrade")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean mustSplit(SaltState state, MinionServer minion) {
        boolean split = false;
        if (state instanceof SaltModuleRun) {
            SaltModuleRun moduleRun = (SaltModuleRun)state;

            Optional<String> mods = getModsString(moduleRun);

            if (mods.isPresent() &&
                    mods.get().contains(PACKAGES_PKGINSTALL) && isSaltUpgrade(state) &&
                    !MinionServerUtils.isSshPushMinion(minion)) {
                // split only for regular minions, salt-ssh minions don't have a salt-minion process
                split = true;
            }
            else if (mods.isPresent() &&
                    mods.get().contains(PACKAGES_PATCHINSTALL) && isSaltUpgrade(state)) {
                split = true;
            }
            else if ("system.reboot".equalsIgnoreCase(moduleRun.getName())) {
                split = true;
            }
        }
        else if (state instanceof SaltSystemReboot) {
            split = true;
        }
        return split;
    }

    private Optional<String> getModsString(SaltModuleRun moduleRun) {
        if (moduleRun.getArgs() == null) {
            return Optional.empty();
        }
        if (moduleRun.getArgs().get("mods") instanceof String) {
            return Optional.of((String)moduleRun.getArgs().get("mods"));
        }
        else if (moduleRun.getArgs().get("mods") instanceof List) {
            return Optional.of(((List)moduleRun.getArgs().get("mods"))
                    .stream()
                    .collect(Collectors.joining(","))
                    .toString());
        }
        return Optional.empty();
    }

    /**
     * Remove action chain SLS files.
     * @param actionChainId an Action Chain ID
     * @param minionId a minion ID
     * @param chunk the chunk number
     * @param actionChainFailed whether the Action Chain failed or not
     */
    public void removeActionChainSLSFiles(Long actionChainId, String minionId, int chunk,
                                          boolean actionChainFailed) {
        MinionServerFactory.findByMinionId(minionId).ifPresent(minionServer -> {
            Path targetDir = getTargetDir();
            Path targetFilePath = Paths.get(targetDir.toString(),
                    getActionChainSLSFileName(actionChainId, minionServer, chunk));
            // Add specified SLS chunk file to remove list
            deleteSlsAndRefs(targetDir, targetFilePath);

            if (actionChainFailed) {
                // Add also next SLS chunks because the Action Chain failed and these
                // files are not longer needed.
                String filePattern = ACTIONCHAIN_SLS_FILE_PREFIX + actionChainId +
                        "_" + minionServer.getMachineId() + "_*.sls";
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetDir, filePattern)) {
                    stream.forEach(slsFile -> {
                        deleteSlsAndRefs(targetDir,  slsFile);
                    });
                } catch (IOException e) {
                    LOG.warn("Error deleting action chain files", e);
                }
            }
        });
    }

    /**
     * Remove all action chains files for the given minion and action chain.
     * @param minion the minion
     * @param actionChainId optionally, the id of the action chain
     */
    public void removeActionChainSLSFilesForMinion(MinionServer minion, Optional<Long> actionChainId) {
        Path targetDir = getTargetDir();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetDir,
                ACTIONCHAIN_SLS_FILE_PREFIX + actionChainId.map(id -> Long.toString(id)).orElse("") +
                        "_" + minion.getMachineId() + "_*.sls")) {
            stream.forEach(slsFile -> {
                deleteSlsAndRefs(targetDir,  slsFile);
            });
        }
        catch (IOException e) {
            LOG.warn("Error deleting action chain files", e);
        }
    }

    private void deleteSlsAndRefs(Path targetDir, Path slsFile) {
        List<Path> toDelete = new LinkedList<>();
        toDelete.add(targetDir.resolve(slsFile));
        // Parse the action chains state files and gather file refs
        List<String> slsFileRefs = findFileRefsToDelete(slsFile);
        toDelete.addAll(slsFileRefs.stream()
                .map(f -> suseManagerStatesFilesRoot.resolve(f))
                .collect(Collectors.toList()));

        for (Path path : toDelete) {
            try {
                Files.deleteIfExists(path);
            }
            catch (IOException e) {
                LOG.warn("Error deleting action chain file " + path.toString(), e);
            }
        }
    }

    /**
     * Public only for unit tests.
     * Collect all salt:// references from the given file in order to delete them.
     * @param targetFilePath the sls file path
     * @return all file references
     */
    public List<String> findFileRefsToDelete(Path targetFilePath) {
        try {
            String slsContent = FileUtils.readFileToString(targetFilePath.toFile());
            // first remove line containing ssh_extra_filerefs because it contains
            // all the files used for an action chain and we want to delete
            // salt:// refs that belong only to the given file
            slsContent = slsContent.replaceAll("ssh_extra_filerefs.+", "");
            Matcher m = SALT_FILE_REF.matcher(slsContent);
            List<String> res = new LinkedList<>();
            int start = 0;
            while (m.find(start)) {
                String ref = m.group(2);
                start = m.start() + 1;
                if (refInList(DEFAULT_TOPS, ref) || refInList(ACTION_STATES_LIST, ref)) {
                    // skip refs to tops and action states
                    continue;
                }
                if (ref.startsWith(ACTIONCHAIN_SLS_FOLDER + "/" + ACTIONCHAIN_SLS_FILE_PREFIX)) {
                    // skip actionchain/actionchain_<chainid>_>minionuuid>_<chunk>.sls
                    continue;
                }

                res.add(ref);
            }
            return res;
        }
        catch (IOException e) {
            LOG.error("Could not collect salt:// references from file " + targetFilePath, e);
            return Collections.emptyList();
        }
    }

    private boolean refInList(List<String> refList, String fileRef) {
        return refList.stream().anyMatch(listRef ->
            fileRef.startsWith(listRef)
        );
    }

    /**
     * Generate file name for the action chain chunk file.
     * Public only for unit tests.
     *
     * @param actionChainId an Action Chain ID
     * @param minionServer a minion instance
     * @param chunk a chunk number
     * @return the file name
     */
    public static String getActionChainSLSFileName(Long actionChainId, MinionServer minionServer, int chunk) {
        return (ACTIONCHAIN_SLS_FILE_PREFIX + Long.toString(actionChainId) +
                "_" + minionServer.getMachineId() + "_" + Integer.toString(chunk) + ".sls");
    }

    private void saveChunkSLS(List<SaltState> states, MinionServer minion, long actionChainId, int chunk) {
        Path targetDir = createActionChainsDir();
        Path targetFilePath = Paths.get(targetDir.toString(),
                getActionChainSLSFileName(actionChainId, minion, chunk));

        try (Writer slsWriter = new FileWriter(targetFilePath.toFile());
             Writer slsBufWriter = new BufferedWriter(slsWriter)) {
            com.suse.manager.webui.utils.SaltStateGenerator saltStateGenerator =
                    new com.suse.manager.webui.utils.SaltStateGenerator(slsBufWriter);
            saltStateGenerator.generate(states.toArray(new SaltState[states.size()]));
        }
        catch (IOException e) {
            LOG.error("Could not write action chain sls " + targetFilePath, e);
            throw new RuntimeException(e);
        }
    }

    private Path getTargetDir() {
        return Paths.get(suseManagerStatesFilesRoot.toString(), ACTIONCHAIN_SLS_FOLDER);
    }

    /**
     * @param suseManagerStatesFilesRootIn to set
     */
    public void setSuseManagerStatesFilesRoot(Path suseManagerStatesFilesRootIn) {
        this.suseManagerStatesFilesRoot = suseManagerStatesFilesRootIn;
    }

    /**
     * Create the Salt state id string specific to the given action chain and action.
     *
     * @param actionChainId action chain id
     * @param actionId action id
     * @return state id string
     */
    public static String createStateId(long actionChainId, Long actionId) {
        return ACTION_STATE_ID_PREFIX + actionChainId +
                ACTION_STATE_ID_ACTION_PREFIX + actionId;
    }

    /**
     * Value class encapsulating the components of a state id
     * used in action chain state files.
     */
    public static final class ActionChainStateId {

        private long actionChainId;
        private long actionId;
        private int chunk;

        /**
         * @param actionChainIdIn action chain id
         * @param actionIdIn action id
         * @param chunkIn chunk number
         */
        public ActionChainStateId(long actionChainIdIn, long actionIdIn, int chunkIn) {
            this.actionChainId = actionChainIdIn;
            this.actionId = actionIdIn;
            this.chunk = chunkIn;
        }

        /**
         * @return actionChainId to get
         */
        public long getActionChainId() {
            return actionChainId;
        }

        /**
         * @return actionId to get
         */
        public long getActionId() {
            return actionId;
        }

        /**
         * @return chunk to get
         */
        public int getChunk() {
            return chunk;
        }
    }

    /**
     * Parse a Salt state id used in action chain sls files.
     * @param stateId the state id string
     * @return the action chain id, action id and chunk
     */
    public static Optional<ActionChainStateId> parseActionChainStateId(String stateId) {
        Matcher m = ACTION_STATE_PATTERN.matcher(stateId);
        if (m.find() && m.groupCount() == 3) {
            try {
                return Optional.of(
                        new ActionChainStateId(
                                Long.parseLong(m.group(1)),
                                Long.parseLong(m.group(2)),
                                Integer.parseInt(m.group(3))
                        )
                );
            }
            catch (NumberFormatException e) {
                LOG.error("Error parsing action chain state id: " + stateId, e);
            }
        }
        return Optional.empty();
    }

    /**
     * @param skipSetOwenerIn to set
     */
    public void setSkipSetOwner(boolean skipSetOwenerIn) {
        this.skipSetOwner = skipSetOwenerIn;
    }

    /**
     * Return the path of the action chain specific top file.
     * @param actionChainId - action chain id
     * @param applyHighstateActionId - the id of the apply highstate action
     * @return actionchains/top_[actionChainId]_[actionId].sls
     */
    public String getActionChainTopPath(long actionChainId, long applyHighstateActionId) {
        return ACTIONCHAIN_SLS_FOLDER + "/" + "top_" + actionChainId + "_" + applyHighstateActionId + ".sls";
    }

    /**
     * Generate top file for applying highstate in a salt-ssh action chain execution.
     * @param actionChainId the action chain id
     * @param applyHighstateActionId the id of the apply highstate action
     * @param top the content of the top file
     * @return the reference of the top file with salt:// prefix
     */
    public String generateTop(long actionChainId, long applyHighstateActionId, SaltTop top) {
        String topFile = getActionChainTopPath(actionChainId, applyHighstateActionId);
        createActionChainsDir();
        Path topPath = suseManagerStatesFilesRoot.resolve(topFile);
        try (Writer fout = new FileWriter(topPath.toFile())) {
            SaltStateGenerator generator = new SaltStateGenerator(fout);
            generator.generate(top);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
        return SALT_FS_PREFIX + topFile;
    }

    /**
     * Make sure the {@literal actionchains} subdir exists.
     * @return the {@link Path} of the {@literal} actionchains directory
     */
    private Path createActionChainsDir() {
        Path targetDir = getTargetDir();
        if (!Files.exists(targetDir)) {
            try {
                Files.createDirectories(targetDir);
                if (!skipSetOwner) {
                    FileSystem fileSystem = FileSystems.getDefault();
                    UserPrincipalLookupService service = fileSystem.getUserPrincipalLookupService();
                    UserPrincipal tomcatUser = service.lookupPrincipalByName("tomcat");
                    Files.setOwner(targetDir, tomcatUser);
                }
            }
            catch (IOException e) {
                LOG.error("Could not create action chain directory " + targetDir, e);
                throw new RuntimeException(e);
            }
        }
        return targetDir;
    }
}
