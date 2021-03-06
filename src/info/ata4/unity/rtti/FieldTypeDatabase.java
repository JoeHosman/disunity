/*
 ** 2013 August 10
 **
 ** The author disclaims copyright to this source code.  In place of
 ** a legal notice, here is a blessing:
 **    May you do good and not evil.
 **    May you find forgiveness for yourself and forgive others.
 **    May you share freely, never taking more than you give.
 */
package info.ata4.unity.rtti;

import info.ata4.io.DataReader;
import info.ata4.io.DataWriter;
import info.ata4.io.socket.IOSocket;
import info.ata4.io.socket.Sockets;
import info.ata4.log.LogUtils;
import info.ata4.unity.asset.AssetFile;
import info.ata4.unity.asset.FieldTypeNode;
import info.ata4.unity.asset.ObjectInfo;
import info.ata4.unity.asset.VersionInfo;
import info.ata4.unity.util.ClassID;
import info.ata4.unity.util.UnityVersion;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

/**
 *
 * @author Nico Bergemann <barracuda415 at yahoo.de>
 */
public class FieldTypeDatabase {
    
    private static final Logger L = LogUtils.getLogger();
    
    public static final int VERSION = 1;
    public static final String FILENAME = "structdb.dat";
    
    private static FieldTypeDatabase instance;

    public static FieldTypeDatabase getInstance() {
        if (instance == null) {
            instance = new FieldTypeDatabase();
        }
        return instance;
    }
    
    private HashMap<Pair<Integer, UnityVersion>, FieldTypeNode> nodeMap = new HashMap<>();
    private int learned;
    
    private FieldTypeDatabase() {
        load();
    }
    
    public int getLearned() {
        return learned;
    }
    
    public Map<Pair<Integer, UnityVersion>, FieldTypeNode> getFieldTypeMap() {
        return Collections.unmodifiableMap(nodeMap);
    }
    
    private void load() {
        L.info("Loading type database");
        
        // read database file, external or internal otherwise
        InputStream is;
        try {
            Path jarFile = Paths.get(FieldTypeDatabase.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dbFile = jarFile.resolveSibling(FILENAME);
            String dbPath = "/resources/" + FILENAME;

            if (Files.exists(dbFile)) {
                is = Files.newInputStream(dbFile);
            } else {
                is = getClass().getResourceAsStream(dbPath);
            }
            
            if (is == null) {
                throw new IOException("Type database file not found");
            }
        } catch (URISyntaxException | IOException ex) {
            L.log(Level.SEVERE, "Can't open type database", ex);
            return;
        }

        try (IOSocket socket = Sockets.forInputStream(new BufferedInputStream(is))) {
            DataReader in = new DataReader(socket);

            // read header
            int dbVersion = in.readInt();

            if (dbVersion != VERSION) {
                throw new RuntimeException("Wrong database version");
            }

            // read field node table
            int fieldNodeSize = in.readInt();
            List<FieldTypeNode> fieldNodes = new ArrayList<>(fieldNodeSize);

            for (int i = 0; i < fieldNodeSize; i++) {
                FieldTypeNode fieldNode = new FieldTypeNode();
                fieldNode.read(in);
                fieldNodes.add(fieldNode);
            }

            // read version string table
            int versionSize = in.readInt();
            List<UnityVersion> versions = new ArrayList<>(versionSize);

            for (int i = 0; i < versionSize; i++) {
                versions.add(new UnityVersion(in.readStringNull()));
            }

            // read mapping data
            int fieldNodeKeySize = in.readInt();

            for (int i = 0; i < fieldNodeKeySize; i++) {
                int index = in.readInt();
                int classID = in.readInt();
                int revisionIndex = in.readInt();
                
                UnityVersion version = versions.get(revisionIndex);
                FieldTypeNode fieldNode = fieldNodes.get(index);

                addNode(classID, version, fieldNode);
            }
        } catch (IOException ex) {
            L.log(Level.SEVERE, "Can't read type database", ex);
        }
    }
    
    private void save() {
        L.info("Saving type database");
        
        // write database file
        Path dbFile = Paths.get(FILENAME);
        try (IOSocket socket = Sockets.forBufferedWriteFile(dbFile, WRITE, CREATE, TRUNCATE_EXISTING)) {
            DataWriter out = new DataWriter(socket);
            
            // write header
            out.writeInt(VERSION);

            // write field node table
            Set<FieldTypeNode> fieldNodes = new HashSet<>(nodeMap.values());
            Map<FieldTypeNode, Integer> fieldNodeMap = new HashMap<>();

            out.writeInt(fieldNodes.size());

            int index = 0;
            for (FieldTypeNode fieldNode : fieldNodes) {
                fieldNodeMap.put(fieldNode, index++);
                fieldNode.write(out);
            }

            // write version string table
            Set<UnityVersion> versions = new HashSet<>();
            Map<UnityVersion, Integer> versionMap = new HashMap<>();

            for (Map.Entry<Pair<Integer, UnityVersion>, FieldTypeNode> entry : nodeMap.entrySet()) {
                versions.add(entry.getKey().getRight());
            }

            out.writeInt(versions.size());

            index = 0;
            for (UnityVersion version : versions) {
                versionMap.put(version, index++);
                out.writeStringNull(version.toString());
            }

            // write mapping data
            out.writeInt(nodeMap.entrySet().size());

            for (Map.Entry<Pair<Integer, UnityVersion>, FieldTypeNode> entry : nodeMap.entrySet()) {
                index = fieldNodeMap.get(entry.getValue());
                Pair<Integer, UnityVersion> fieldNodeKey = entry.getKey();

                int classID = fieldNodeKey.getLeft();
                UnityVersion version = fieldNodeKey.getRight();

                out.writeInt(index);
                out.writeInt(classID);
                out.writeInt(versionMap.get(version));
            }
        } catch (IOException ex) {
            L.log(Level.SEVERE, "Can't write type database", ex);
        }
    }
    
    public void fill(AssetFile asset) {
        VersionInfo versionInfo = asset.getVersionInfo();
        
        if (versionInfo.getUnityRevision() == null) {
            L.warning("unityRevision = null");
            return;
        }
        
        Set<Integer> classIDs = getClassIDs(asset.getObjectInfoMap().values());
        for (Integer classID : classIDs) {
            FieldTypeNode typeNode = getNode(classID, versionInfo.getUnityRevision(), false);
            if (typeNode != null) {
                asset.getTypeTree().put(classID, typeNode);
            }
        }
    }
    
    public int learn(AssetFile asset) {
        Map<Integer, FieldTypeNode> typeTree = asset.getTypeTree();
        
        if (typeTree.isEmpty()) {
            L.warning("Empty type tree");
            return 0;
        }
        
        VersionInfo versionInfo = asset.getVersionInfo();
        
        if (versionInfo.getUnityRevision() == null) {
            L.warning("unityRevision = null");
            return 0;
        }
        
        int learnedNew = 0;
        
        // merge the TypeTree map with the database field map
        Set<Integer> classIDs = getClassIDs(asset.getObjectInfoMap().values());
        for (Integer classID : classIDs) {
            FieldTypeNode fieldType = typeTree.get(classID);
            String fieldClassName = ClassID.getNameForID(classID);

            if (fieldType == null) {
                continue;
            }
            
            FieldTypeNode fieldTypeMapped = getNode(classID, versionInfo.getUnityRevision(), true);

            if (fieldTypeMapped == null) {
                fieldTypeMapped = fieldType;
                L.log(Level.INFO, "New: {0} ({1})", new Object[]{classID, fieldClassName});
                addNode(classID, versionInfo.getUnityRevision(), fieldTypeMapped);
                learnedNew++;
            }

            // check the hashes, they must be identical at this point
            int hash1 = fieldType.hashCode();
            int hash2 = fieldTypeMapped.hashCode();

            if (hash1 != hash2) {
                L.log(Level.WARNING, "Database hash mismatch for {0}: {1} != {2}", new Object[] {fieldTypeMapped.getType().getTypeName(), hash1, hash2});
            }

            if (fieldClassName == null) {
                L.log(Level.WARNING, "Unknown ClassID {0}, suggested name: {1}", new Object[] {classID, fieldType.getType().getTypeName()});
            }
        }
        
        learned += learnedNew;
        
        return learnedNew;
    }
    
    public void update() {
        if (learned > 0) {
            L.log(Level.INFO, "Adding {0} new type(s) to database", learned);
            save();
            learned = 0;
        }
    }
    
    public FieldTypeNode getNode(int classID, UnityVersion version, boolean strict) {
        FieldTypeNode fieldNode = nodeMap.get(new ImmutablePair<>(classID, version));

        // if set to strict, only return exact matches or null
        if (fieldNode != null || strict) {
            return fieldNode;
        }

        FieldTypeNode fieldNodeB = null;
        UnityVersion versionB = null;

        FieldTypeNode fieldNodeC = null;
        UnityVersion versionC = null;

        for (Map.Entry<Pair<Integer, UnityVersion>, FieldTypeNode> entry : nodeMap.entrySet()) {
            Pair<Integer, UnityVersion> fieldNodeKey = entry.getKey();
            if (fieldNodeKey.getLeft() == classID) {
                FieldTypeNode fieldNodeEntry = entry.getValue();
                UnityVersion revisionEntry = fieldNodeKey.getRight();

                if (revisionEntry.getMajor() == version.getMajor()) {
                    if (revisionEntry.getMinor() == version.getMinor()) {
                        // if major and minor versions match, it will probably work
                        return fieldNodeEntry;
                    } else {
                        // suboptimal choice
                        fieldNodeB = fieldNodeEntry;
                        versionB = revisionEntry;
                    }
                }

                // worst choice
                fieldNodeC = fieldNodeEntry;
                versionC = revisionEntry;
            }
        }

        // return less perfect match
        if (fieldNodeB != null) {
            L.log(Level.WARNING, "Unprecise match for ClassID {0} (required: {1}, available: {2})", new Object[]{classID, version, versionB});
            return fieldNodeB;
        }

        // return field node from any revision as the very last resort
        if (fieldNodeC != null) {
            L.log(Level.WARNING, "Bad match for ClassID {0} (required: {1}, available: {2})", new Object[]{classID, version, versionC});
            return fieldNodeC;
        }

        // no matches at all
        return null;
    }

    public void addNode(int classID, UnityVersion revision, FieldTypeNode fieldNode) {
        nodeMap.put(new ImmutablePair<>(classID, revision), fieldNode);
    }

    private Set<Integer> getClassIDs(Collection<ObjectInfo> paths) {
        Set<Integer> classIDs = new TreeSet<>();
        
        for (ObjectInfo path : paths) {
            classIDs.add(path.getClassID());
        }
        
        return classIDs;
    }
}
