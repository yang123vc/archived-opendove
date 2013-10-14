package org.opendaylight.opendove.odmc.implementation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

import org.apache.felix.dm.Component;
import org.opendaylight.controller.clustering.services.CacheConfigException;
import org.opendaylight.controller.clustering.services.CacheExistException;
import org.opendaylight.controller.clustering.services.IClusterContainerServices;
import org.opendaylight.controller.clustering.services.IClusterServices;
import org.opendaylight.controller.networkconfig.neutron.NeutronNetwork;
import org.opendaylight.opendove.odmc.IfOpenDoveDomainCRU;
import org.opendaylight.opendove.odmc.IfOpenDoveNetworkCRU;
import org.opendaylight.opendove.odmc.IfOpenDoveServiceApplianceCRU;
import org.opendaylight.opendove.odmc.IfOpenDoveSwitchCRU;
import org.opendaylight.opendove.odmc.IfSBDoveVGWVNIDMappingCRUD;
import org.opendaylight.opendove.odmc.OpenDoveCRUDInterfaces;
import org.opendaylight.opendove.odmc.OpenDoveConcurrentBackedMap;
import org.opendaylight.opendove.odmc.OpenDoveDomain;
import org.opendaylight.opendove.odmc.OpenDoveEndpoint;
import org.opendaylight.opendove.odmc.OpenDoveNVP;
import org.opendaylight.opendove.odmc.OpenDoveNetwork;
import org.opendaylight.opendove.odmc.OpenDoveObject;
import org.opendaylight.opendove.odmc.OpenDoveServiceAppliance;
import org.opendaylight.opendove.odmc.OpenDoveSwitch;
import org.opendaylight.opendove.odmc.OpenDoveVGWVNIDMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenDoveBidirectionalInterfaces implements IfOpenDoveSwitchCRU, IfOpenDoveNetworkCRU,
        IfOpenDoveDomainCRU, IfOpenDoveServiceApplianceCRU, IfSBDoveVGWVNIDMappingCRUD  {
    private static final Logger logger = LoggerFactory.getLogger(OpenDoveSBInterfaces.class);
    private String containerName = null;

    private IClusterContainerServices clusterContainerService = null;
    private ConcurrentMap<String, OpenDoveServiceAppliance> doveServiceApplianceDB;
    private OpenDoveConcurrentBackedMap networkMap, domainMap, switchMap, vgwVNIDMap;
    private ConcurrentMap<Integer, OpenDoveObject> objectDB;


    // methods needed for creating caches

    void setClusterContainerService(IClusterContainerServices s) {
        logger.debug("Cluster Service set");
        this.clusterContainerService = s;
    }

    void unsetClusterContainerService(IClusterContainerServices s) {
        if (this.clusterContainerService == s) {
            logger.debug("Cluster Service removed!");
            this.clusterContainerService = null;
        }
    }

    @SuppressWarnings("deprecation")
    private void allocateCache() {
        if (this.clusterContainerService == null) {
            logger.error("un-initialized clusterContainerService, can't create cache");
            return;
        }
        logger.debug("Creating Cache for OpenDOVE");
        try {
            // DOVE caches
          this.clusterContainerService.createCache("openDoveServiceAppliances",
                 EnumSet.of(IClusterServices.cacheMode.NON_TRANSACTIONAL));
        } catch (CacheConfigException cce) {
            logger.error("Southbound Caches couldn't be created for OpenDOVE -  check cache mode");
        } catch (CacheExistException cce) {
            logger.error("Cache for OpenDOVE already exists, destroy and recreate");
        }
        logger.debug("Southbound Caches successfully created for OpenDOVE");
    }

    @SuppressWarnings({ "unchecked", "deprecation" })
    private void retrieveCache() {
        if (this.clusterContainerService == null) {
            logger.error("un-initialized clusterContainerService, can't retrieve cache");
            return;
        }
        logger.debug("Retrieving cache for openDoveServiceAppliances");
        doveServiceApplianceDB = (ConcurrentMap<String, OpenDoveServiceAppliance>) this.clusterContainerService
               .getCache("openDoveServiceAppliances");
        if (doveServiceApplianceDB == null) {
            logger.error("Cache couldn't be retrieved for openDoveServiceAppliances");
        }
        logger.debug("Cache was successfully retrieved for openDoveServiceAppliances");
        logger.debug("Retrieving cache for openDoveObjects");
        objectDB = (ConcurrentMap<Integer, OpenDoveObject>) this.clusterContainerService
                .getCache("openDoveObjects");
        if (objectDB == null) {
            logger.error("Cache couldn't be retrieved for openDoveSubnets");
        }
        domainMap = new OpenDoveConcurrentBackedMap(objectDB, OpenDoveDomain.class);
        networkMap = new OpenDoveConcurrentBackedMap(objectDB, OpenDoveNetwork.class);
        switchMap = new OpenDoveConcurrentBackedMap(objectDB, OpenDoveSwitch.class);
        vgwVNIDMap = new OpenDoveConcurrentBackedMap(objectDB, OpenDoveVGWVNIDMapping.class);
    }

    @SuppressWarnings("deprecation")
    private void destroyCache() {
        if (this.clusterContainerService == null) {
            logger.error("un-initialized clusterMger, can't destroy cache");
            return;
        }
        logger.debug("Destroying Caches for OpenDove");
        this.clusterContainerService.destroyCache("openDoveServiceAppliances");
    }

    private void startUp() {
        allocateCache();
        retrieveCache();
    }

    /**
     * Function called by the dependency manager when all the required
     * dependencies are satisfied
     *
     */
    void init(Component c) {
        Dictionary<?, ?> props = c.getServiceProperties();
        if (props != null) {
            this.containerName = (String) props.get("containerName");
            logger.debug("Running containerName: {}", this.containerName);
        } else {
            // In the Global instance case the containerName is empty
            this.containerName = "";
        }
        startUp();
    }

    /**
     * Function called by the dependency manager when at least one dependency
     * become unsatisfied or when the component is shutting down because for
     * example bundle is being stopped.
     *
     */
    void destroy() {
        destroyCache();
    }

    /**
     * Function called by dependency manager after "init ()" is called and after
     * the services provided by the class are registered in the service registry
     *
     */
    void start() {
    }

    /**
     * Function called by the dependency manager before the services exported by
     * the component are unregistered, this will be followed by a "destroy ()"
     * calls
     *
     */
    void stop() {
    }

    /*
     *  Code to Support South Bound DOVE Service Appliance Interfaces.
     */

    public boolean dsaIPExists(String ip) {
        Iterator<OpenDoveServiceAppliance> i = doveServiceApplianceDB.values().iterator();
        while (i.hasNext()) {
            OpenDoveServiceAppliance d = i.next();
            if (d.getIP().compareTo(ip) == 0)
                return true;
        }
        return false;
    }

    public OpenDoveServiceAppliance getDoveServiceAppliance(String dsaUUID) {
        return(doveServiceApplianceDB.get(dsaUUID));
    }
    public void addDoveServiceAppliance(String dsaUUID, OpenDoveServiceAppliance openDoveDSA) {
        doveServiceApplianceDB.putIfAbsent(dsaUUID, openDoveDSA);
    }

    public List<OpenDoveServiceAppliance> getAppliances() {
        List<OpenDoveServiceAppliance> answer = new ArrayList<OpenDoveServiceAppliance>();
        answer.addAll(doveServiceApplianceDB.values());
        return answer;
    }

    public List<OpenDoveServiceAppliance> getRoleAssignedDcsAppliances() {
        List<OpenDoveServiceAppliance> answer = new ArrayList<OpenDoveServiceAppliance>();
        Iterator<OpenDoveServiceAppliance> i = doveServiceApplianceDB.values().iterator();
        while (i.hasNext()) {
            OpenDoveServiceAppliance d = i.next();
            if (d.get_isDCS () == true )
                answer.add(d);
        }
        return answer;
    }

    public boolean applianceExists(String saUUID) {
        return doveServiceApplianceDB.containsKey(saUUID);
    }
    public boolean dsaIPConflict(String ip, String uuid) {
        Iterator<OpenDoveServiceAppliance> i = doveServiceApplianceDB.values().iterator();
        while (i.hasNext()) {
            OpenDoveServiceAppliance d = i.next();
            if (d.getIP().compareTo(ip) == 0)  {
                if  (d.getUUID().compareTo(uuid) == 0) {
                    // No Conflict
                    return false;
                } else {
                    // IP Address Conflict
                    return true;
                }
            }
        }
        // IP Address Conflict
        return false;
    }

    // this method uses reflection to update an object from it's delta.

    private boolean overwrite(Object target, Object delta) {
        Method[] methods = target.getClass().getMethods();

        for(Method toMethod: methods){
            if(toMethod.getDeclaringClass().equals(target.getClass())
                    && toMethod.getName().startsWith("set")){

                String toName = toMethod.getName();
                String fromName = toName.replace("set", "get");

                try {
                    Method fromMethod = delta.getClass().getMethod(fromName);
                    Object value = fromMethod.invoke(delta, (Object[])null);
                    if(value != null){
                        toMethod.invoke(target, value);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    return false;
                }
            }
        }
        return true;
    }

    public boolean updateDoveServiceAppliance(String dsaUUID, OpenDoveServiceAppliance input) {
        OpenDoveServiceAppliance target = doveServiceApplianceDB.get(dsaUUID);
        return overwrite(target, input);
    }

    // code to support domain interfaces (including URI)

    public boolean domainExists(String domainUUID) {
        return(domainMap.containsKey(domainUUID));
    }

    public boolean domainExistsByName(String name) {
        Iterator<OpenDoveObject> i = domainMap.values().iterator();
        while (i.hasNext()) {
            OpenDoveDomain d = (OpenDoveDomain) i.next();
            if (d.getName().compareTo(name) == 0)
                return true;
        }
        return false;
    }

    public OpenDoveDomain getDomain(String domainUUID) {
        return(OpenDoveDomain) (domainMap.get(domainUUID));
    }

    public OpenDoveDomain getDomainByName(String name) {
        Iterator<OpenDoveObject> i = domainMap.values().iterator();
        while (i.hasNext()) {
            OpenDoveDomain d = (OpenDoveDomain) i.next();
            if (d.getName().compareTo(name) == 0)
                return d;
        }
        return null;
    }

    public void addDomain(String domainUUID, OpenDoveDomain domain) {
        domainMap.putIfAbsent(domainUUID, domain);
    }

    public void addNetworkToDomain(String domainUUID, OpenDoveNetwork network) {
        if (domainExists(domainUUID)) {
            OpenDoveDomain domain = (OpenDoveDomain) domainMap.get(domainUUID);
            domain.addNetwork(network);
        }
    }

    public List<OpenDoveDomain> getDomains() {
        List<OpenDoveDomain> answer = new ArrayList<OpenDoveDomain>();
        Iterator<OpenDoveObject> i = domainMap.values().iterator();
        while (i.hasNext()) {
            answer.add((OpenDoveDomain) i.next());
        }
        return answer;
    }

    public List<OpenDoveServiceAppliance> getDCSList(String saUUID) {
        //TODO: FILL IN
        List<OpenDoveServiceAppliance> answer = new ArrayList<OpenDoveServiceAppliance>();
        return answer;
    }

    // code to support SB network interfaces (including URI)

    public boolean networkExists(String networkUUID) {
        return(networkMap.containsKey(networkUUID));
    }

    public boolean networkExistsByVnid(int vnid) {
        Iterator<OpenDoveObject> i = networkMap.values().iterator();
        while (i.hasNext()) {
            OpenDoveNetwork n = (OpenDoveNetwork) i.next();
            if (n.getVnid() == vnid)
                return true;
        }
        return false;
    }

    public OpenDoveNetwork getNetwork(String networkUUID) {
        return (OpenDoveNetwork) (networkMap.get(networkUUID));
    }

    public OpenDoveNetwork getNetworkByVnid(int vnid) {
        Iterator<OpenDoveObject> i = networkMap.values().iterator();
        while (i.hasNext()) {
            OpenDoveNetwork n = (OpenDoveNetwork) i.next();
            if (n.getVnid() == vnid)
                return n;
        }
        return null;
    }

    public OpenDoveNetwork getNetworkByName(String name) {
        Iterator<OpenDoveObject> i = networkMap.values().iterator();
        while (i.hasNext()) {
            OpenDoveNetwork n = (OpenDoveNetwork) i.next();
            if (n.getName().equals(name))
                return n;
        }
        return null;
    }

    public void addNetwork(String networkUUID, OpenDoveNetwork network) {
        networkMap.putIfAbsent(networkUUID, network);
        IfOpenDoveDomainCRU sbInterface = OpenDoveCRUDInterfaces.getIfDoveDomainCRU(this);
        if (sbInterface != null)
            sbInterface.addNetworkToDomain(network.getDomain_uuid(), network);
    }

    public int allocateVNID() {
        boolean done = false;
        while (!done) {
            long candidateVNID = OpenDoveNetwork.getNext() & 0x0000000000FFFFFF;
            if (!networkExistsByVnid((int) candidateVNID))
                return (int) candidateVNID;
        }
        return 0;
    }

    public List<OpenDoveNetwork> getNetworks() {
        List<OpenDoveNetwork> answer = new ArrayList<OpenDoveNetwork>();
        Iterator<OpenDoveObject> i = networkMap.values().iterator();
        while (i.hasNext()) {
            answer.add((OpenDoveNetwork) i.next());
        }
        return answer;
    }

    public List<OpenDoveEndpoint> getEndpoints(String saUUID) {
        //TODO: FILL IN
        List<OpenDoveEndpoint> answer = new ArrayList<OpenDoveEndpoint>();
        return answer;
    }

    // IfOpenDoveSwitchCRU

    public boolean switchExists(String switchUUID) {
        return(switchMap.containsKey(switchUUID));
    }

    public OpenDoveSwitch getSwitch(String switchUUID) {
        return (OpenDoveSwitch) (switchMap.get(switchUUID));
    }

    public void addSwitch(String switchUUID, OpenDoveSwitch oSwitch) {
        switchMap.putIfAbsent(switchUUID, oSwitch);
    }

    public List<OpenDoveSwitch> getSwitches() {
        List<OpenDoveSwitch> answer = new ArrayList<OpenDoveSwitch>();
        Iterator<OpenDoveObject> i = switchMap.values().iterator();
        while (i.hasNext()) {
            answer.add((OpenDoveSwitch) i.next());
        }
        return answer;
    }

	public List<OpenDoveNVP> getStats(String queryIPAddr, String queryVNID,
			String queryMAC) {
		// TODO Fill in
        List<OpenDoveNVP> answer = new ArrayList<OpenDoveNVP>();
        return answer;
	}
	
    // IfSBDoveVGWVNIDMappingCRUD methods

    public boolean vgwVNIDMappingExists(String mappingUUID) {
        return(vgwVNIDMap.containsKey(mappingUUID));
    }

    public OpenDoveVGWVNIDMapping getVgwVNIDMapping(String mappingUUID) {
        return (OpenDoveVGWVNIDMapping) (vgwVNIDMap.get(mappingUUID));
    }

    public void addVgwVNIDMapping(String mappingUUID,
            OpenDoveVGWVNIDMapping mapping) {
        vgwVNIDMap.putIfAbsent(mappingUUID, mapping);
    }

    public List<OpenDoveVGWVNIDMapping> getVgwVNIDMappings() {
        List<OpenDoveVGWVNIDMapping> answer = new ArrayList<OpenDoveVGWVNIDMapping>();
        Iterator<OpenDoveObject> i = vgwVNIDMap.values().iterator();
        while (i.hasNext()) {
            answer.add((OpenDoveVGWVNIDMapping) i.next());
        }
        return answer;
    }

    public void removeVgwVNIDMapping(String mappingUUID) {
        vgwVNIDMap.remove(mappingUUID);
    }

	public void updateRule(String mappingUUID, OpenDoveVGWVNIDMapping delta) {
		OpenDoveVGWVNIDMapping target = (OpenDoveVGWVNIDMapping) vgwVNIDMap.get(mappingUUID);
        overwrite(target, delta);
		vgwVNIDMap.update(mappingUUID, target);		
	}
}
