package edu.brown.hstore.conf;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.collections15.map.ListOrderedMap;

import edu.brown.BaseTestCase;
import edu.brown.hstore.conf.HStoreConf;
import edu.brown.hstore.conf.HStoreConf.Conf;
import edu.brown.utils.FileUtil;

public class TestHStoreConf extends BaseTestCase {

    private static final Map<String, Object> properties = new ListOrderedMap<String, Object>();
    static {
        properties.put("site.markov_path_caching", false);              // Boolean
        properties.put("site.markov_path_caching_threshold", 0.91919);  // Double
        properties.put("site.memory", 19999);                           // Long
    }
    
    private HStoreConf hstore_conf;
    private final String groups[] = { "global", "client", "site" };
    
    @Override
    protected void setUp() throws Exception {
        assert(HStoreConf.isInitialized());
        hstore_conf = HStoreConf.singleton();
    }
    
    /**
     * testValidateReplacedBy
     */
    public void testValidateReplacedBy() throws Exception {
        // Make sure that if that anything with a replacedBy field is marked as 
        // deprecated and the replacedBy points to another valid field
        for (Conf handle : hstore_conf.getHandles().values()) {
            assertNotNull(handle);
            Map<Field, ConfigProperty> fields = handle.getConfigProperties();
            assertFalse(fields.isEmpty());
            for (Entry<Field, ConfigProperty> e : fields.entrySet()) {
                Field f = e.getKey();
                ConfigProperty cp = e.getValue();
                String name = handle.prefix + "." + f.getName();
                
                if (cp.replacedBy().isEmpty() == false) {
                    Deprecated d = f.getAnnotation(Deprecated.class);
                    assertNotNull("Missing Deprecated annotation for " + name, d);
                    String replacedBy = cp.replacedBy();
                    assertTrue("Invalid mapping " + name + "->" + replacedBy, hstore_conf.hasParameter(replacedBy));
                }
            } // FOR
        }
    }
    
    /**
     * testValidateDefaultTypes
     */
    public void testValidateDefaultTypes() throws Exception {
        // Make sure that if they have a default value that it matches the type
        // of the configuration parameter
        for (Conf handle : hstore_conf.getHandles().values()) {
            assertNotNull(handle);
            Map<Field, ConfigProperty> fields = handle.getConfigProperties();
            assertFalse(fields.isEmpty());
            for (Entry<Field, ConfigProperty> e : fields.entrySet()) {
                Field f = e.getKey();
                ConfigProperty cp = e.getValue();
                String name = handle.prefix + "." + f.getName();
                
                // BOOLEAN
                if (cp.defaultBoolean()) {
                    assertEquals(name, boolean.class, f.getType());
                    assertFalse(name, cp.defaultNull());
                }
                // INTEGER
                else if (cp.defaultInt() != Integer.MIN_VALUE) {
                    assertEquals(name, int.class, f.getType());
                    assertFalse(name, cp.defaultNull());
                }
                // LONG
                else if (cp.defaultLong() != Long.MIN_VALUE) {
                    assertEquals(name, long.class, f.getType());
                    assertFalse(name, cp.defaultNull());
                }
                // DOUBLE
                else if (cp.defaultDouble() != Double.MIN_VALUE) {
                    assertEquals(name, double.class, f.getType());
                    assertFalse(name, cp.defaultNull());
                }
                // STRING
                else if (cp.defaultNull()) {
                    assertEquals(name, String.class, f.getType());
                }
                else if (cp.defaultString().isEmpty() == false) {
                    assertEquals(name, String.class, f.getType());
                }
            } // FOR
        }
    }
    
    
    /**
     * testPopulateDependencies
     */
    public void testPopulateDependencies() throws Exception {
        // Just check to see whether any parameter that references
        // another parameter in its defaultString has that other parameter's
        // value to set to its value 
        String origParam = "global.defaulthost";
        Object origValue = hstore_conf.get(origParam);
//        System.err.println(origParam + " => " + origValue);
        assertNotNull(origValue);
        
        String newParam = "client.hosts";
        Object newValue = hstore_conf.get(newParam);
//        System.err.println(newParam + " => " + newValue);
        assertEquals(origValue, newValue);
    }
    
    /**
     * testPopulateDependenciesDefaultString
     */
    public void testPopulateDependenciesDefaultString() throws Exception {
        // Check that we can successfully populate the value of a dependency
        // into the defaultString value for another parameter
        String origParam = "global.temp_dir";
        String origValue = hstore_conf.get(origParam).toString();
        System.err.println(origParam + " => " + origValue);
        assertNotNull(origValue);
        
        String newParam = "global.log_dir";
        String newValue = hstore_conf.get(newParam).toString();
        System.err.println(newParam + " => " + newValue);
        assertTrue(newValue, newValue.startsWith(origValue));
    }
    
    /**
     * testSetReplacedParameters
     */
    public void testSetReplacedParameters() throws Exception {
        // Check that if we change a deprecated parameter that the parameter
        // that replaces it also gets changed
        String origParam = "client.processesperclient";
        String newParam = "client.threads_per_host";
        
        int expected = 9999;
        hstore_conf.set(origParam, expected);
        
        Object origValue = hstore_conf.get(origParam);
        assertEquals(expected, origValue);
        
        Object newValue = hstore_conf.get(newParam);
        assertEquals(expected, newValue);
    }
    
    /**
     * testMakeIndexHTML
     */
    public void testMakeIndexHTML() throws Exception {
        for (String prefix : groups) {
            String contents = HStoreConfUtil.makeIndexHTML(hstore_conf, prefix);
            assertNotNull(contents);
            assertFalse(contents.isEmpty());
        } // FOR
    }
    
    /**
     * testMakeHTML
     */
    public void testMakeHTML() throws Exception {
        for (String prefix : groups) {
            String contents = HStoreConfUtil.makeHTML(hstore_conf, prefix);
            assertNotNull(contents);
            assertFalse(contents.isEmpty());
        } // FOR
    }
    
    /**
     * testBuildXML
     */
    public void testBuildXML() throws Exception {
        for (String prefix : groups) {
            String contents = HStoreConfUtil.makeBuildCommonXML(hstore_conf, prefix);
            assertNotNull(contents);
            assertFalse(contents.isEmpty());
        } // FOR
    }
    
    /**
     * testLoadFromFile
     */
    public void testLoadFromFile() throws Exception {
        // First make sure that the values aren't the same
        Class<?> confClass = hstore_conf.site.getClass();
        for (String k : properties.keySet()) {
            Field field = confClass.getField(k.split("\\.")[1]);
            assertNotSame(k, properties.get(k), field.get(hstore_conf.site));
        } // FOR
        
        // Then create a config file
        String contents = "";
        for (Entry<String, Object> e : properties.entrySet()) {
            contents += String.format("%s = %s\n", e.getKey(), e.getValue()); 
        } // FOR
        File f = FileUtil.writeStringToTempFile(contents, "properties", true);
        
        // And load it in. Now the values should be what we expect them to be
        hstore_conf.loadFromFile(f);
        for (String k : properties.keySet()) {
            Field field = confClass.getField(k.split("\\.")[1]);
            assertEquals(k, properties.get(k), field.get(hstore_conf.site));
        } // FOR
    }
    
    /**
     * testIsConfParameterValid
     */
    public void testIsConfParameterValid() {
        String valid[] = {
            "site.planner_caching",
            "client.txnrate",
            "global.temp_dir"
        };
        for (String name : valid) {
            assertTrue("Unexpected '" + name + "'", HStoreConf.isConfParameter(name));
        } // FOR
    }
    
    /**
     * testIsConfParameterInvalid
     */
    public void testIsConfParameterInvalid() {
        String invalid[] = {
            "evanjones.canadian",
            "site.squirrels",
            "siteplanner_caching"
        };
        for (String name : invalid) {
            assertFalse("Unexpected '" + name + "'", HStoreConf.isConfParameter(name));
        } // FOR
    }
    
}
