/**
 * Copyright (C) 2012-2015 Thales Services SAS.
 *
 * This file is part of AuthZForce.
 *
 * AuthZForce is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * AuthZForce is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with AuthZForce. If not, see <http://www.gnu.org/licenses/>.
 */
package org.ow2.authzforce.pap.dao.flatfile;

import java.beans.ConstructorProperties;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import oasis.names.tc.xacml._3_0.core.schema.wd_17.IdReferenceType;
import oasis.names.tc.xacml._3_0.core.schema.wd_17.PolicySet;

import org.ow2.authzforce.core.pap.api.dao.DomainDAOClient;
import org.ow2.authzforce.core.pap.api.dao.DomainsDAO;
import org.ow2.authzforce.core.pap.api.dao.PolicyDAOClient;
import org.ow2.authzforce.core.pap.api.dao.PolicyVersionDAOClient;
import org.ow2.authzforce.core.pap.api.dao.ReadableDomainProperties;
import org.ow2.authzforce.core.pap.api.dao.ReadablePdpProperties;
import org.ow2.authzforce.core.pap.api.dao.TooManyPoliciesException;
import org.ow2.authzforce.core.pap.api.dao.WritableDomainProperties;
import org.ow2.authzforce.core.pap.api.dao.WritablePdpProperties;
import org.ow2.authzforce.core.pdp.api.EnvironmentPropertyName;
import org.ow2.authzforce.core.pdp.api.JaxbXACMLUtils;
import org.ow2.authzforce.core.pdp.api.PDP;
import org.ow2.authzforce.core.pdp.api.PolicyVersion;
import org.ow2.authzforce.core.pdp.impl.DefaultEnvironmentProperties;
import org.ow2.authzforce.core.pdp.impl.PDPImpl;
import org.ow2.authzforce.core.pdp.impl.PdpConfigurationParser;
import org.ow2.authzforce.core.pdp.impl.PdpModelHandler;
import org.ow2.authzforce.core.xmlns.pdp.Pdp;
import org.ow2.authzforce.core.xmlns.pdp.StaticRefBasedRootPolicyProvider;
import org.ow2.authzforce.pap.dao.file.xmlns.DomainProperties;
import org.ow2.authzforce.pap.dao.file.xmlns.StaticFileBasedDAORefPolicyProvider;
import org.ow2.authzforce.xmlns.pdp.ext.AbstractAttributeProvider;
import org.ow2.authzforce.xmlns.pdp.ext.AbstractPolicyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.util.ResourceUtils;
import org.xml.sax.SAXException;

import com.fasterxml.uuid.EthernetAddress;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedGenerator;

/**
 * Filesystem-based policy domain repository DAO
 * 
 * @param <VERSION_DAO_CLIENT>
 *            Domain policy version DAO client implementation class
 * 
 * @param <POLICY_DAO_CLIENT>
 *            Domain policy DAO client implementation class
 * 
 * @param <DOMAIN_DAO_CLIENT>
 *            Domain DAO client implementation class
 *
 */
public final class FlatFileBasedDomainsDAO<VERSION_DAO_CLIENT extends PolicyVersionDAOClient, POLICY_DAO_CLIENT extends PolicyDAOClient, DOMAIN_DAO_CLIENT extends DomainDAOClient<FlatFileBasedDomainDAO<VERSION_DAO_CLIENT, POLICY_DAO_CLIENT>>>
		implements DomainsDAO<DOMAIN_DAO_CLIENT>
{

	private static final Logger LOGGER = LoggerFactory
			.getLogger(FlatFileBasedDomainsDAO.class);

	private static class ReadableDomainPropertiesImpl implements
			ReadableDomainProperties
	{

		private final String domainId;
		private final String description;
		private final String externalId;

		private ReadableDomainPropertiesImpl(String domainId,
				String description, String externalId)
		{
			assert domainId != null;

			this.domainId = domainId;
			this.description = description;
			this.externalId = externalId;
		}

		@Override
		public String getInternalId()
		{
			return domainId;
		}

		@Override
		public String getExternalId()
		{
			return externalId;
		}

		@Override
		public String getDescription()
		{
			return description;
		}

	}

	private static class ReadablePdpPropertiesImpl implements
			ReadablePdpProperties
	{

		private final IdReferenceType rootPolicyRef;
		private final long lastModified;
		private final List<IdReferenceType> enabledPolicies;

		private ReadablePdpPropertiesImpl(IdReferenceType rootPolicyRef,
				long lastModified, List<IdReferenceType> enabledPolicies)
		{
			assert rootPolicyRef != null;
			assert enabledPolicies != null && !enabledPolicies.isEmpty();

			this.rootPolicyRef = rootPolicyRef;
			this.lastModified = lastModified;
			this.enabledPolicies = enabledPolicies;
		}

		@Override
		public IdReferenceType getRootPolicyRef()
		{
			return this.rootPolicyRef;
		}

		@Override
		public long getLastModified()
		{
			return this.lastModified;
		}

		@Override
		public List<IdReferenceType> getEnabledPolicies()
		{
			return this.enabledPolicies;
		}

	}

	private static final IllegalArgumentException ILLEGAL_CONSTRUCTOR_ARGS_EXCEPTION = new IllegalArgumentException(
			"One of the following FileBasedDomainsDAO constructor arguments is undefined although required: domainsRoot == null || domainTmpl == null || schema == null || pdpModelHandler == null || domainDAOClientFactory == null || policyDAOClientFactory == null");

	private static final IllegalArgumentException NULL_DOMAIN_ID_ARG_EXCEPTION = new IllegalArgumentException(
			"Undefined domain ID arg");

	private static final IllegalArgumentException ILLEGAL_POLICY_NOT_STATIC_EXCEPTION = new IllegalArgumentException(
			"One of the policy finders in the domain PDP configuration is not static, or one of the policies required by PDP cannot be statically resolved");

	private static final RuntimeException NON_STATIC_POLICY_EXCEPTION = new RuntimeException(
			"Unexpected error: Some policies are not statically resolved (pdp.getStaticRootAndRefPolicies() == null)");

	private static final IllegalArgumentException NULL_POLICY_ARGUMENT_EXCEPTION = new IllegalArgumentException(
			"Null policySet arg");
	private static final TreeSet<PolicyVersion> EMPTY_TREE_SET = new TreeSet<>();
	private static final IllegalArgumentException NULL_DOMAIN_PROPERTIES_ARGUMENT_EXCEPTION = new IllegalArgumentException(
			"Null domain properties arg");
	private static final IllegalArgumentException NULL_PDP_PROPERTIES_ARGUMENT_EXCEPTION = new IllegalArgumentException(
			"Null domain PDP properties arg");
	private static final IllegalArgumentException NULL_ROOT_POLICY_REF_ARGUMENT_EXCEPTION = new IllegalArgumentException(
			"Invalid domain PDP properties arg: rootPolicyRef undefined");
	private static final IllegalArgumentException NULL_ATTRIBUTE_PROVIDERS_ARGUMENT_EXCEPTION = new IllegalArgumentException(
			"Null attributeProviders arg");

	/**
	 * Domain properties XSD location
	 */
	public static final String DOMAIN_PROPERTIES_XSD_LOCATION = "classpath:org.ow2.authzforce.pap.dao.file.properties.xsd";

	/**
	 * Name of domain properties file
	 */
	public static final String DOMAIN_PROPERTIES_FILENAME = "properties.xml";

	/**
	 * Name of PDP configuration file
	 */
	public static final String DOMAIN_PDP_CONFIG_FILENAME = "pdp.xml";

	/**
	 * Must start with a non-base64url character
	 */
	public static final String FILE_BACKUP_SUFFIX = ".old";

	// private static final Logger LOGGER =
	// LoggerFactory.getLogger(SecurityDomain.class);

	private static final JAXBContext DOMAIN_PROPERTIES_JAXB_CONTEXT;

	static
	{
		try
		{
			DOMAIN_PROPERTIES_JAXB_CONTEXT = JAXBContext
					.newInstance(DomainProperties.class);
		} catch (JAXBException e)
		{
			throw new RuntimeException(
					"Error creating JAXB context for (un)marshalling domain properties (XML)",
					e);
		}
	}

	private static final Schema DOMAIN_PROPERTIES_SCHEMA;

	static
	{
		final SchemaFactory schemaFactory = SchemaFactory
				.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
		try
		{
			DOMAIN_PROPERTIES_SCHEMA = schemaFactory.newSchema(ResourceUtils
					.getURL(DOMAIN_PROPERTIES_XSD_LOCATION));
		} catch (FileNotFoundException e)
		{
			throw new RuntimeException("Domain properties schema not found", e);
		} catch (SAXException e)
		{
			throw new RuntimeException("Invalid domain properties schema file",
					e);
		}
	}

	private static final DirectoryStream.Filter<Path> DIRECTORY_FILTER = new DirectoryStream.Filter<Path>()
	{

		@Override
		public boolean accept(Path path)
		{
			return Files.isDirectory(path);
		}

	};

	private final TimeBasedGenerator uuidGen;

	/**
	 * Initializes a UUID generator that generates UUID version 1. It is
	 * thread-safe and uses the host MAC address as the node field if
	 * useRandomAddressBasedUUID = false, in which case UUID uniqueness across
	 * multiple hosts (e.g. in a High-Availability architecture) is guaranteed.
	 * If this is used by multiple hosts to generate UUID for common objects
	 * (e.g. in a High Availability architecture), it is critical that clocks of
	 * all hosts be synchronized (e.g. with a common NTP server). If no MAC
	 * address is available, e.g. no network connection, set
	 * useRandomAddressBasedUUID = true to use a random multicast address
	 * instead as node field.
	 * 
	 * @see <a href=
	 *      "http://www.cowtowncoder.com/blog/archives/2010/10/entry_429.html">
	 *      More on Java UUID Generator (JUG), a word on performance</a>
	 * @see <a href=
	 *      "http://johannburkard.de/blog/programming/java/Java-UUID-generators-compared.html">
	 *      Java UUID generators compared</a>
	 * 
	 * @return UUID v1
	 */
	private static TimeBasedGenerator initUUIDGenerator(
			boolean useRandomAddressBasedUUID)
	{

		final EthernetAddress macAddress;
		if (useRandomAddressBasedUUID)
		{
			macAddress = EthernetAddress.constructMulticastAddress();
		} else
		{
			macAddress = EthernetAddress.fromInterface();
			if (macAddress == null)
			{
				throw new RuntimeException(
						"Failed to create UUID generator (based on time and MAC address): no valid Ethernet MAC address found. Please enable at least one network interface for global uniqueness of UUIDs. If not, you can fall back to UUID generation based on random multicast address instead by setting argument: useRandomAddressBasedUUID = true");
			}
		}

		return Generators.timeBasedGenerator(macAddress);
	}

	private final Path domainsRootDir;

	/**
	 * Maps domainId to domain
	 */
	private final ConcurrentMap<String, DOMAIN_DAO_CLIENT> domainMap = new ConcurrentHashMap<>();

	/**
	 * Maps domain externalId to unique API-defined domainId in domainMap keys
	 */
	private final ConcurrentMap<String, String> domainIDsByExternalId = new ConcurrentHashMap<>();

	private final Path domainTmplDirPath;

	private final PdpModelHandler pdpModelHandler;

	private final long domainDirToMemSyncIntervalSec;

	private final DomainDAOClient.Factory<VERSION_DAO_CLIENT, POLICY_DAO_CLIENT, FlatFileBasedDomainDAO<VERSION_DAO_CLIENT, POLICY_DAO_CLIENT>, DOMAIN_DAO_CLIENT> domainDAOClientFactory;
	private final PolicyDAOClient.Factory<VERSION_DAO_CLIENT, POLICY_DAO_CLIENT> policyDAOClientFactory;
	private final PolicyVersionDAOClient.Factory<VERSION_DAO_CLIENT> policyVersionDAOClientFactory;

	private final int maxNumOfPoliciesPerDomain;

	private final int maxNumOfVersionsPerPolicy;

	private final TooManyPoliciesException maxNumOfPoliciesReachedException;

	private final TooManyPoliciesException maxNumOfVersionsReachedException;

	private final boolean removeOldestVersionsIfMaxExceeded;

	/**
	 * MMust be called this method in a block synchronized on 'domainsRootDir'
	 * 
	 * @param domainId
	 *            ID of domain to be removed
	 */
	private void removeDomainFromCache(String domainId) throws IOException
	{
		assert domainId != null;
		final DOMAIN_DAO_CLIENT domain = domainMap.remove(domainId);
		if (domain == null)
		{
			// already removed
			return;
		}

		try (final FlatFileBasedDomainDAO<VERSION_DAO_CLIENT, POLICY_DAO_CLIENT> domainDAO = domain
				.getDAO())
		{
			final String externalId = domainDAO.getExternalId();
			if (externalId != null)
			{
				domainIDsByExternalId.remove(externalId);
			}
		}
	}

	private final class FileBasedDomainDAOImpl implements
			FlatFileBasedDomainDAO<VERSION_DAO_CLIENT, POLICY_DAO_CLIENT>
	{
		private final String domainId;

		private final Path domainDirPath;

		/**
		 * 
		 * @return null if domain (directory) no longer exists
		 * @throws IOException
		 */
		@Override
		public DomainProperties sync() throws IOException,
				IllegalArgumentException
		{
			/*
			 * synchonized block makes sure no other thread is messing with the
			 * domain directory while we synchronize it to domainMap. See also
			 * method #add(Properties)
			 */
			final DomainProperties props;
			synchronized (domainDirPath)
			{
				LOGGER.debug("Domain {}: synchronizing...", domainId);
				if (Files.notExists(domainDirPath, LinkOption.NOFOLLOW_LINKS))
				{
					// DOMAIN DIRECTORY REMOVED
					LOGGER.info(
							"Domain {} removed from filesystem -> removing from cache",
							domainId);

					synchronized (domainsRootDir)
					{
						removeDomainFromCache(domainId);
					}
					return null;
				}

				// SYNC DOMAIN DIRECTORY
				props = syncDomainProperties();
				final boolean isChanged = syncPDP();
				if (isChanged)
				{
					LOGGER.info(
							"Domain {}: synchronization: change to PDP files since last sync -> PDP reloaded",
							domainId);
				}

				LOGGER.debug("Domain {}: synchronization done.", domainId);
			}

			return props;
		}

		/**
		 * this is run by domainDirToMemSyncScheduler
		 */
		private final class DirectoryToMemorySyncTask implements Runnable
		{
			@Override
			public void run()
			{
				try
				{
					sync();

				} catch (Throwable e)
				{
					LOGGER.error(
							"Domain '{}': error occurred during synchronization",
							domainId, e);
				}
			}
		}

		private final File propFile;

		private final File pdpConfFile;

		private final Path policyParentDirPath;
		private final String policyFilenameSuffix;

		private final DefaultEnvironmentProperties pdpConfEnvProps;

		private final DirectoryStream.Filter<Path> policyFilePathFilter;

		private final ScheduledExecutorService dirToMemSyncScheduler;

		/*
		 * Last time when external ID in domain maps was synced with repository
		 * (properties file in domain directory (set respectively by
		 * saveProperties() and loadProperties() methods only)
		 */
		private volatile long lastExternalIdSyncedTime = 0;

		private volatile String cachedExternalId = null;

		private volatile PDPImpl pdp = null;

		/*
		 * Last time when PDP was (re)loaded from repository (pdp conf and
		 * policy files in domain directory) (set only by reloadPDP)
		 */
		private volatile long lastPdpSyncedTime = 0;

		/**
		 * Constructs end-user policy admin domain
		 * 
		 * @param domainDirPath
		 *            domain directory
		 * @param jaxbCtx
		 *            JAXB context for marshalling/unmarshalling configuration
		 *            data
		 * @param confSchema
		 *            domain's XML configuration schema
		 * @param pdpModelHandler
		 *            PDP configuration model handler
		 * @param domainMapEntry
		 *            proxy to entry in domains map where all domains are
		 *            registry (e.g. to self-remove from the map)
		 * @param props
		 *            new domain properties for new domain creation, null if no
		 *            specific properties (use default properties)
		 * @throws IllegalArgumentException
		 *             Invalid configuration files in {@code domainDir}
		 * @throws IOException
		 *             Error loading configuration file(s) from or persisting
		 *             {@code props} (if not null) to {@code domainDir}
		 */
		private FileBasedDomainDAOImpl(Path domainDirPath,
				WritableDomainProperties props) throws IOException
		{
			assert domainDirPath != null;

			this.domainId = domainDirPath.getFileName().toString();

			// domainDir
			FlatFileDAOUtils.checkFile("Domain directory", domainDirPath, true,
					true);
			this.domainDirPath = domainDirPath;

			// PDP configuration parser environment properties, e.g. PARENT_DIR
			// for replacement in configuration strings
			this.pdpConfEnvProps = new DefaultEnvironmentProperties(
					Collections.singletonMap(
							EnvironmentPropertyName.PARENT_DIR, domainDirPath
									.toUri().toString()));

			// PDP config file
			this.pdpConfFile = domainDirPath
					.resolve(DOMAIN_PDP_CONFIG_FILENAME).toFile();

			// Get policy directory from PDP conf
			// (refPolicyProvider/policyLocation pattern)
			final Pdp pdpConf = loadPDPConfTmpl();

			// Get the refpolicies parent directory and suffix from PDP conf
			// (refPolicyProvider)
			final AbstractPolicyProvider refPolicyProvider = pdpConf
					.getRefPolicyProvider();
			if (!(refPolicyProvider instanceof StaticFileBasedDAORefPolicyProvider))
			{
				// critical error
				throw new RuntimeException(
						"Invalid PDP configuration of domain '" + domainId
								+ "' in file '" + pdpConfFile
								+ "': refPolicyProvider is not an instance of "
								+ StaticFileBasedDAORefPolicyProvider.class
								+ " as expected.");
			}

			final StaticFileBasedDAORefPolicyProvider fileBasedRefPolicyProvider = (StaticFileBasedDAORefPolicyProvider) refPolicyProvider;
			// replace any ${PARENT_DIR} placeholder in policy location pattern
			final String policyLocation = pdpConfEnvProps
					.replacePlaceholders(fileBasedRefPolicyProvider
							.getPolicyLocationPattern());
			final Entry<Path, String> result = FlatFileDAORefPolicyProviderModule
					.validateConf(policyLocation);
			this.policyParentDirPath = result.getKey();
			FlatFileDAOUtils.checkFile("Domain policies directory",
					policyParentDirPath, true, true);

			this.policyFilenameSuffix = result.getValue();
			this.policyFilePathFilter = new FlatFileDAOUtils.SuffixMatchingDirectoryStreamFilter(
					policyFilenameSuffix);

			// propFile
			this.propFile = domainDirPath.resolve(DOMAIN_PROPERTIES_FILENAME)
					.toFile();

			if (props == null)
			{
				/*
				 * Validate and reload domain properties file, load in
				 * particular the externalId in the externalId-to-domainId map
				 */
				getDomainProperties();
			} else
			{
				// set/save properties and update PDP
				setDomainProperties(props);
			}

			// Just load the PDP from the files
			reloadPDP();

			/*
			 * Schedule periodic domain directory-to-memory synchronization task
			 * if sync enabled (strictly positive interval defined)
			 */
			if (domainDirToMemSyncIntervalSec > 0)
			{
				// Sync enabled
				final DirectoryToMemorySyncTask syncTask = new DirectoryToMemorySyncTask();
				dirToMemSyncScheduler = Executors.newScheduledThreadPool(1);
				dirToMemSyncScheduler.scheduleWithFixedDelay(syncTask,
						domainDirToMemSyncIntervalSec,
						domainDirToMemSyncIntervalSec, TimeUnit.SECONDS);
				LOGGER.info(
						"Domain {}: scheduled periodic directory-to-memory synchronization (initial delay={}s, period={}s)",
						domainId, domainDirToMemSyncIntervalSec,
						domainDirToMemSyncIntervalSec);
			} else
			{
				dirToMemSyncScheduler = null;
			}
		}

		@Override
		public String getDomainId()
		{
			return this.domainId;
		}

		@Override
		public String getExternalId()
		{
			return this.cachedExternalId;
		}

		/**
		 * Reload PDP from configuration files, (including policy files, aka
		 * "PRP" in XACML)
		 * 
		 * @throws IOException
		 *             I/O error reading from confFile
		 * @throws IllegalArgumentException
		 *             Invalid PDP configuration in confFile
		 */
		private void reloadPDP() throws IOException, IllegalArgumentException
		{
			lastPdpSyncedTime = System.currentTimeMillis();
			// test if PDP conf valid, and update the domain's PDP only if valid
			final PDPImpl newPDP = PdpConfigurationParser.getPDP(pdpConfFile,
					pdpModelHandler);
			// did not throw exception, so valid
			// update the domain's PDP
			if (pdp != null)
			{
				pdp.close();
			}

			pdp = newPDP;

			// Check that all policies used by PDP are statically resolved
			// Indeed, dynamic policy resolution is not supported by this PAP
			// DAO implementation
			final Map<String, PolicyVersion> staticPoliciesById = pdp
					.getStaticRootAndRefPolicies();
			if (staticPoliciesById == null)
			{
				throw ILLEGAL_POLICY_NOT_STATIC_EXCEPTION;
			}
		}

		/**
		 * Reload PDP with input JAXB conf, and persist conf to file if PDP
		 * reloaded successfully
		 * 
		 * @param pdpConfTemplate
		 *            original PDP configuration template from file, i.e. before
		 *            any replacement of property placeholders like
		 *            ${PARENT_DIR}; saved/marshalled to file PDP update
		 *            succeeds
		 * @throws IllegalArgumentException
		 * @throws IOException
		 */
		private void reloadPDP(Pdp pdpConfTmpl)
				throws IllegalArgumentException, IOException
		{
			// test if PDP conf valid, and update the domain's PDP only if valid
			final PDPImpl newPDP = PdpConfigurationParser.getPDP(pdpConfTmpl,
					pdpConfEnvProps);
			// did not throw exception, so valid
			// Commit/save the new PDP conf
			try
			{
				pdpModelHandler.marshal(pdpConfTmpl, pdpConfFile);
			} catch (JAXBException e)
			{
				// critical error: we should not end up with an invalid PDP
				// configuration file, so we consider an I/O error
				throw new IOException(
						"Error writing new PDP configuration of domain '"
								+ domainId + "'", e);
			}

			// update the domain's PDP
			if (pdp != null)
			{
				pdp.close();
			}

			pdp = newPDP;
		}

		private void saveProperties(WritableDomainProperties props)
				throws IOException
		{
			final Marshaller marshaller;
			try
			{
				marshaller = DOMAIN_PROPERTIES_JAXB_CONTEXT.createMarshaller();
				marshaller.setProperty(Marshaller.JAXB_ENCODING,
						StandardCharsets.UTF_8.name());
			} catch (JAXBException e)
			{
				// critical error
				throw new RuntimeException(
						"Error creating JAXB unmarshaller for domain properties (XML)",
						e);
			}

			marshaller.setSchema(DOMAIN_PROPERTIES_SCHEMA);
			try
			{
				/*
				 * The rootPolicyRef is in another file (PDP configuration
				 * file). We cannot marshall more generic
				 * ManagedResourceProperties because it does not have
				 * 
				 * @XmlRootElement
				 */
				marshaller.marshal(new DomainProperties(props.getDescription(),
						props.getExternalId()), propFile);
			} catch (JAXBException e)
			{
				throw new IOException(
						"Error persisting properties (XML) of domain '"
								+ domainId + "'", e);
			}
		}

		private DomainProperties loadProperties() throws IOException
		{
			final Unmarshaller unmarshaller;
			try
			{
				unmarshaller = DOMAIN_PROPERTIES_JAXB_CONTEXT
						.createUnmarshaller();
			} catch (JAXBException e)
			{
				// critical error
				throw new RuntimeException(
						"Error creating JAXB unmarshaller for domain properties (XML)",
						e);
			}

			unmarshaller.setSchema(DOMAIN_PROPERTIES_SCHEMA);
			final JAXBElement<DomainProperties> jaxbElt;
			try
			{
				jaxbElt = unmarshaller.unmarshal(new StreamSource(propFile),
						DomainProperties.class);
			} catch (JAXBException e)
			{
				throw new IOException(
						"Error getting properties (XML) of domain '" + domainId
								+ "'", e);
			}

			return jaxbElt.getValue();
		}

		/**
		 * Update externalId (cached value) and external-id-to-domain map.
		 * 
		 * @param newExternalId
		 */
		private void updateCachedExternalId(String newExternalId)
		{
			/*
			 * Synchronized block makes sure the domain's cachedExternalId is
			 * synchronized with the corresponding value in
			 * domainIDsByExternalId map for that domain's Id
			 */
			synchronized (domainsRootDir)
			{
				if (cachedExternalId == null)
				{
					// externalId not previously set
					if (newExternalId != null)
					{
						domainIDsByExternalId.put(newExternalId, domainId);

					}
				} else if (!cachedExternalId.equals(newExternalId))
				{
					// externalId was set and has changed or unset
					domainIDsByExternalId.remove(cachedExternalId);
					if (newExternalId != null)
					{
						domainIDsByExternalId.put(newExternalId, domainId);
					}
				}

				cachedExternalId = newExternalId;
			}
		}

		@Override
		public ReadableDomainProperties setDomainProperties(
				WritableDomainProperties props) throws IOException,
				IllegalArgumentException
		{
			if (props == null)
			{
				throw NULL_DOMAIN_PROPERTIES_ARGUMENT_EXCEPTION;
			}

			// Synchronize changes on PDP (and other domain conf data) from
			// multiple threads, keep minimal things in the synchronized block
			synchronized (domainDirPath)
			{
				// let's sync to directory
				lastExternalIdSyncedTime = System.currentTimeMillis();
				// validate and save new properties to disk
				saveProperties(props);
				// update externalId (cached value) and external-id-to-domain
				// map
				updateCachedExternalId(props.getExternalId());
			}

			return new ReadableDomainPropertiesImpl(domainId,
					props.getDescription(), props.getExternalId());

		}

		/**
		 * Must be called within a synchronized(domainDirPath) block
		 */
		private DomainProperties syncDomainProperties() throws IOException
		{
			final long lastModifiedTime = propFile.lastModified();
			final boolean isFileModified = lastModifiedTime > lastExternalIdSyncedTime;
			// let's sync
			lastExternalIdSyncedTime = System.currentTimeMillis();
			final DomainProperties props = loadProperties();
			if (isFileModified)
			{
				if(LOGGER.isDebugEnabled()) {
					LOGGER.debug("Domain {}: file '{}' (lastModifiedTime = {}) changed since last sync -> updating externalId in externalId-to-domain map", domainId, propFile, new Date(lastModifiedTime));
				}
				
				updateCachedExternalId(props.getExternalId());
			}

			return props;
		}

		@Override
		public ReadableDomainProperties getDomainProperties()
				throws IOException
		{

			final DomainProperties props;
			synchronized (domainDirPath)
			{
				props = syncDomainProperties();
			}

			return new ReadableDomainPropertiesImpl(domainId,
					props.getDescription(), props.getExternalId());
		}

		/**
		 * Loads original PDP configuration template from file, before any
		 * replacement of property placeholders like ${PARENT_DIR}
		 * 
		 * @return original PDP configuration from file (no property like
		 *         PARENT_DIR replaced in the process)
		 * @throws IOException
		 */
		private Pdp loadPDPConfTmpl() throws IOException
		{
			try
			{
				return pdpModelHandler.unmarshal(new StreamSource(pdpConfFile),
						Pdp.class);
			} catch (JAXBException e)
			{
				// critical error: we should not end up with an invalid PDP
				// configuration file, so we consider an I/O error
				throw new IOException(
						"Error reading PDP configuration of domain '"
								+ domainId + "'", e);
			}
		}

		private boolean syncPdpPolicies() throws IllegalArgumentException,
				IOException
		{
			final Map<String, PolicyVersion> usedPolicies = pdp
					.getStaticRootAndRefPolicies();
			if (usedPolicies == null)
			{
				throw NON_STATIC_POLICY_EXCEPTION;
			}

			for (final Entry<String, PolicyVersion> usedPolicy : usedPolicies
					.entrySet())
			{
				final Path policyPath = getPolicyVersionPath(
						usedPolicy.getKey(), usedPolicy.getValue());
				if (!Files.exists(policyPath, LinkOption.NOFOLLOW_LINKS))
				{
					// used policy file removed, this is a significant change
					reloadPDP();
					return true;
				}

				// used policy file is there, checked whether changed since last
				// sync
				final long policyLastModifiedTime = Files.getLastModifiedTime(
						policyPath, LinkOption.NOFOLLOW_LINKS).toMillis();
				if (policyLastModifiedTime > lastPdpSyncedTime)
				{
					if(LOGGER.isDebugEnabled()) {
						LOGGER.debug("Domain {}: policy file '{}' (lastModifiedTime = {}) changed since last sync -> reloading PDP", domainId, policyPath, new Date(policyLastModifiedTime));
					}
					
					reloadPDP();
					return true;
				}
			}

			return false;
		}

		/**
		 * Reload PDP only if a change to one of PDP files (main configuration,
		 * policies...) has been detected. Should be called inside a
		 * synchronized(domainDirPath) block
		 * 
		 * @return true iff PDP was actually changed by synchronization
		 *         (reloaded)
		 * @throws IOException
		 * @throws IllegalArgumentException
		 */
		private boolean syncPDP() throws IllegalArgumentException, IOException
		{
			// Check for change in PDP's main conf file
			final long pdpConfLastModifiedTime = pdpConfFile.lastModified();
			if (pdpConfLastModifiedTime > lastPdpSyncedTime)
			{
				if(LOGGER.isDebugEnabled()) {
					LOGGER.debug("Domain {}: PDP conf file '{}' (lastModifiedTime = {}) changed since last sync -> reloading PDP", domainId, pdpConfFile, new Date(pdpConfLastModifiedTime));
				}
				
				reloadPDP();
				return true;
			}

			// check for changes in PDP active policies
			return syncPdpPolicies();
		}

		@Override
		public ReadablePdpProperties setOtherPdpProperties(
				WritablePdpProperties properties) throws IOException,
				IllegalArgumentException
		{
			if (properties == null)
			{
				throw NULL_PDP_PROPERTIES_ARGUMENT_EXCEPTION;
			}

			final IdReferenceType newRootPolicyRef = properties
					.getRootPolicyRef();
			if (newRootPolicyRef == null)
			{
				throw NULL_ROOT_POLICY_REF_ARGUMENT_EXCEPTION;
			}

			final long lastPdpSyncedTimeCopy;
			final List<IdReferenceType> matchedPolicyRefs;
			synchronized (domainDirPath)
			{
				lastPdpSyncedTime = System.currentTimeMillis();
				// Get current PDP conf that we have to change (only part of it)
				final Pdp pdpConf = loadPDPConfTmpl();
				/*
				 * First check whether rootPolicyRef is the same/unchanged to
				 * avoid useless PDP reload (loading a new PDP is costly)
				 */
				final AbstractPolicyProvider rootPolicyProvider = pdpConf
						.getRootPolicyProvider();
				if (!(rootPolicyProvider instanceof StaticRefBasedRootPolicyProvider))
				{
					// critical error
					throw new RuntimeException(
							"Invalid PDP configuration of domain '"
									+ domainId
									+ "'"
									+ "': rootPolicyProvider is not an instance of "
									+ StaticRefBasedRootPolicyProvider.class
									+ " as expected.");
				}

				final StaticRefBasedRootPolicyProvider staticRefBasedRootPolicyProvider = (StaticRefBasedRootPolicyProvider) rootPolicyProvider;
				// If rootPolicyRef changed, validate/reload the PDP with new
				// rootPolicyRef
				if (!newRootPolicyRef.equals(staticRefBasedRootPolicyProvider
						.getPolicyRef()))
				{
					staticRefBasedRootPolicyProvider
							.setPolicyRef(newRootPolicyRef);
					reloadPDP(pdpConf);
				} else
				{
					// no reload but we sill need to sync PDP to make sure
					// pdp.getStaticRootAndRefPolicies() is up-to-date
					syncPdpPolicies();
				}

				final Map<String, PolicyVersion> pdpEnabledPolicyMap = pdp
						.getStaticRootAndRefPolicies();
				if (pdpEnabledPolicyMap == null)
				{
					throw NON_STATIC_POLICY_EXCEPTION;
				}

				matchedPolicyRefs = new ArrayList<>();
				for (final Entry<String, PolicyVersion> enabledPolicyEntry : pdpEnabledPolicyMap
						.entrySet())
				{
					matchedPolicyRefs.add(new IdReferenceType(
							enabledPolicyEntry.getKey(), enabledPolicyEntry
									.getValue().toString(), null, null));
				}

				/*
				 * lastPdpSyncedTime is not final, therefore we make a defensive
				 * copy of the current value of lastPdpSyncedTime before
				 * returning in case another thread messes with it after we quit
				 * the synchronized block. This is a primitive type, so
				 * assignment copies the value.
				 */
				lastPdpSyncedTimeCopy = lastPdpSyncedTime;
			}

			return new ReadablePdpPropertiesImpl(newRootPolicyRef,
					lastPdpSyncedTimeCopy, matchedPolicyRefs);
		}

		@Override
		public ReadablePdpProperties getOtherPdpProperties() throws IOException
		{

			final long lastPdpSyncedTimeCopy;
			final AbstractPolicyProvider rootPolicyProvider;
			final List<IdReferenceType> matchedPolicyRefs;
			synchronized (domainDirPath)
			{
				final long lastModifiedTime = pdpConfFile.lastModified();
				final boolean isPdpConfModified = lastModifiedTime > lastPdpSyncedTime;
				// let's sync
				lastPdpSyncedTime = System.currentTimeMillis();
				// Get current PDP conf that we have to change (only part of it)
				final Pdp pdpConf = loadPDPConfTmpl();
				rootPolicyProvider = pdpConf.getRootPolicyProvider();
				if (!(rootPolicyProvider instanceof StaticRefBasedRootPolicyProvider))
				{
					// critical error
					throw new RuntimeException(
							"Invalid PDP configuration of domain '"
									+ domainId
									+ "'"
									+ "': rootPolicyProvider is not an instance of "
									+ StaticRefBasedRootPolicyProvider.class
									+ " as expected.");
				}

				if (isPdpConfModified)
				{
					if(LOGGER.isDebugEnabled()) {
						LOGGER.debug("Domain {}: PDP conf file '{}' (lastModifiedTime = {}) changed since last sync -> reloading PDP", domainId, pdpConfFile, new Date(lastModifiedTime));
					}
					
					reloadPDP(pdpConf);
				} else
				{
					syncPdpPolicies();
				}

				final Map<String, PolicyVersion> pdpEnabledPolicyMap = pdp
						.getStaticRootAndRefPolicies();
				if (pdpEnabledPolicyMap == null)
				{
					throw NON_STATIC_POLICY_EXCEPTION;
				}

				matchedPolicyRefs = new ArrayList<>();
				for (final Entry<String, PolicyVersion> enabledPolicyEntry : pdpEnabledPolicyMap
						.entrySet())
				{
					matchedPolicyRefs.add(new IdReferenceType(
							enabledPolicyEntry.getKey(), enabledPolicyEntry
									.getValue().toString(), null, null));
				}

				/*
				 * lastPdpSyncedTime is not final, therefore we make a defensive
				 * copy of the current value of lastPdpSyncedTime before
				 * returning in case another thread messes with it after we quit
				 * the synchronized block. This is a primitive type, so
				 * assignment copies the value.
				 */
				lastPdpSyncedTimeCopy = lastPdpSyncedTime;
			}

			final StaticRefBasedRootPolicyProvider staticRefBasedRootPolicyProvider = (StaticRefBasedRootPolicyProvider) rootPolicyProvider;
			return new ReadablePdpPropertiesImpl(
					staticRefBasedRootPolicyProvider.getPolicyRef(),
					lastPdpSyncedTimeCopy, matchedPolicyRefs);
		}

		/**
		 * Returns the PDP enforcing the domain policy
		 * 
		 * @return domain PDP
		 */
		@Override
		public PDP getPDP()
		{
			return this.pdp;
		}

		@Override
		public List<AbstractAttributeProvider> setAttributeProviders(
				List<AbstractAttributeProvider> attributeproviders)
				throws IOException, IllegalArgumentException
		{
			if (attributeproviders == null)
			{
				throw NULL_ATTRIBUTE_PROVIDERS_ARGUMENT_EXCEPTION;
			}

			// Synchronize changes on PDP (and other domain conf data) from
			// multiple threads, keep minimal things in the synchronized block
			synchronized (domainDirPath)
			{
				lastPdpSyncedTime = System.currentTimeMillis();
				final Pdp pdpConf = loadPDPConfTmpl();
				pdpConf.getAttributeProviders().clear();
				pdpConf.getAttributeProviders().addAll(attributeproviders);
				reloadPDP(pdpConf);
			}

			return attributeproviders;
		}

		/**
		 * Get domain PDP attribute providers
		 * 
		 * @return attribute providers
		 * @throws IOException
		 */
		@Override
		public List<AbstractAttributeProvider> getAttributeProviders()
				throws IOException
		{
			// Synchronize changes on PDP (and other domain conf data) from
			// multiple threads, keep minimal things in the synchronized block
			final Pdp pdpConf;
			synchronized (domainDirPath)
			{
				final long lastModifiedTime = pdpConfFile.lastModified();
				final boolean isFileModified = lastModifiedTime > lastPdpSyncedTime;
				// let's sync
				lastPdpSyncedTime = System.currentTimeMillis();
				pdpConf = loadPDPConfTmpl();
				if (isFileModified)
				{
					if(LOGGER.isDebugEnabled()) {
						LOGGER.debug("Domain {}: PDP conf file '{}' (lastModifiedTime = {}) changed since last sync -> reloading PDP", domainId, pdpConfFile, new Date(lastModifiedTime));
					}
					
					reloadPDP(pdpConf);
				}
			}

			return pdpConf.getAttributeProviders();
		}

		/**
		 * Get policy-specific directory
		 * 
		 * @param policyId
		 * @return policy directory (created or not, i.e. to be created)
		 */
		private Path getPolicyDirectory(String policyId)
		{
			assert policyId != null;
			// Name of directory is base64url-encoded policyID (no padding)
			final String policyDirName = FlatFileDAOUtils
					.base64UrlEncode(policyId);
			return this.policyParentDirPath.resolve(policyDirName);
		}

		/**
		 * Get/load policy from file
		 * 
		 * @param policyFile
		 * @return policy; null if policyFile does not exists or is not a file
		 * @throws IOException
		 */
		private PolicySet loadPolicy(File policyFile) throws IOException
		{
			assert policyFile != null;

			if (!policyFile.exists() || !policyFile.isFile())
			{
				return null;
			}

			final JAXBElement<PolicySet> policyElt;
			try
			{
				final Unmarshaller unmarshaller = JaxbXACMLUtils
						.createXacml3Unmarshaller();
				policyElt = unmarshaller.unmarshal(
						new StreamSource(policyFile), PolicySet.class);
			} catch (JAXBException e)
			{
				throw new IOException("Error getting a policy of domain '"
						+ domainId + "'", e);
			}

			return policyElt.getValue();
		}

		/**
		 * Save/write policy to file
		 * 
		 * @param file
		 *            target file
		 * @throws IOException
		 */
		private void savePolicy(PolicySet policy, File file) throws IOException
		{
			assert policy != null;
			assert file != null;

			try
			{
				final Marshaller marshaller = JaxbXACMLUtils
						.createXacml3Marshaller();
				marshaller.marshal(policy, file);
			} catch (JAXBException e)
			{
				throw new IOException("Error saving policy in domain '"
						+ domainId + "'", e);
			}
		}

		private Path getPolicyVersionPath(Path policyDirPath,
				PolicyVersion version)
		{
			return policyDirPath.resolve(version + policyFilenameSuffix);
		}

		@Override
		public PolicySet addPolicy(PolicySet policySet) throws IOException,
				IllegalArgumentException, TooManyPoliciesException
		{
			if (policySet == null)
			{
				throw NULL_POLICY_ARGUMENT_EXCEPTION;
			}

			final String policyId = policySet.getPolicySetId();
			final Path policyDirPath = getPolicyDirectory(policyId);
			final PolicyVersion policyVersion = new PolicyVersion(
					policySet.getVersion());
			final File policyVersionFile = getPolicyVersionPath(policyDirPath,
					policyVersion).toFile();

			synchronized (domainDirPath)
			{
				if (policyVersionFile.exists())
				{
					/*
					 * conflict: same policy version already exists, return it
					 */
					// make sure the PDP is in sync with the returned policy
					// version
					syncPDP();
					return loadPolicy(policyVersionFile);
				}

				/*
				 * Policy version does not exist, but does the policy has any
				 * version already, i.e. does a directory exist for the policy?
				 */
				final boolean isNewPolicy;
				if (Files.exists(policyDirPath))
				{
					isNewPolicy = false;
				} else
				{
					/*
					 * No such directory -> new policy (and new version a
					 * fortiori) check whether limit of number of policies is
					 * reached
					 */
					if (maxNumOfPoliciesPerDomain > 0)
					{
						int policyCount = 0;
						try (final DirectoryStream<Path> policyParentDirStream = Files
								.newDirectoryStream(policyParentDirPath,
										DIRECTORY_FILTER))
						{
							final Iterator<Path> policyDirIterator = policyParentDirStream
									.iterator();
							while (policyDirIterator.hasNext())
							{
								policyDirIterator.next();
								policyCount++;
							}
						} catch (IOException e)
						{
							throw new IOException(
									"Error listing files in policies directory '"
											+ policyParentDirPath
											+ "' of domain '" + domainId + "'",
									e);
						}

						if (policyCount > maxNumOfPoliciesPerDomain)
						{
							throw maxNumOfPoliciesReachedException;
						}
					}

					try
					{
						Files.createDirectory(policyDirPath);
					} catch (IOException e)
					{
						throw new IOException("Error creating directory '"
								+ policyDirPath + "' for new policy '"
								+ policyId + "' in domain '" + domainId + "'");
					}

					isNewPolicy = true;
				}

				final NavigableSet<PolicyVersion> policyVersions = getPolicyVersions(policyDirPath);
				final int excessOfPolicyVersionsToBeRemoved;

				/*
				 * New policy version. Check whether number of versions > max
				 */
				if (maxNumOfVersionsPerPolicy > 0)
				{
					excessOfPolicyVersionsToBeRemoved = policyVersions.size()
							- maxNumOfVersionsPerPolicy;
					/*
					 * if too many versions after adding the new one, but we
					 * will not remove any to fix it, then throw an error
					 */
					if (excessOfPolicyVersionsToBeRemoved > 0
							&& !removeOldestVersionsIfMaxExceeded)
					{
						/*
						 * Oldest versions will not be removed, therefore we
						 * cannot add policies anymore without exceeding max
						 */
						throw maxNumOfVersionsReachedException;
					}
				} else
				{
					// number of versions per policy is unlimited
					excessOfPolicyVersionsToBeRemoved = 0;
				}

				savePolicy(policySet, policyVersionFile);

				/*
				 * Reload PDP iff the PDP depends on the policy updated here
				 * (e.g. we are changing the latest policy version, and our PDP
				 * implementation uses the latest version if no specific version
				 * is specified)
				 */
				// Let's sync the PDP first before we check the actual active
				// policies
				syncPDP();
				final PolicyVersion requiredPolicyVersion = pdp
						.getStaticRootAndRefPolicies().get(policyId);
				if (requiredPolicyVersion != null)
				{
					/*
					 * the added policy (same policy ID but different version)
					 * is currently used by the PDP
					 */
					try
					{
						reloadPDP();
					} catch (Throwable e)
					{
						// PDP reload failed -> rollback
						if (policyVersionFile.delete())
						{
							if (isNewPolicy)
							{
								// new policy (first version) -> empty directory
								try
								{
									Files.delete(policyDirPath);
								} catch (IOException ioe)
								{
									throw new IOException(
											"Failed to delete directory of new policy '"
													+ policyId
													+ "' after removing single version that caused PDP instantiation failure: '"
													+ policyDirPath
													+ "'. Please delete manually and reload the domain.",
											ioe);
								}
							}
						} else
						{
							throw new IOException(
									"Failed to delete file of invalid policy that caused PDP instantiation failure: '"
											+ policyVersionFile
											+ "'. Please delete manually and reload the domain.",
									e);
						}

						throw e;
					}
				}

				// PDP reloaded successfully
				if (excessOfPolicyVersionsToBeRemoved > 0)
				{
					/*
					 * too many versions, we need to remove some (the oldest
					 * that are not required by the PDP)
					 */
					final Iterator<PolicyVersion> oldestToLatestVersionIterator = policyVersions
							.descendingIterator();
					int numRemoved = 0;
					while (oldestToLatestVersionIterator.hasNext()
							&& numRemoved < excessOfPolicyVersionsToBeRemoved)
					{
						final PolicyVersion version = oldestToLatestVersionIterator
								.next();
						// remove only if not required
						if (version.equals(requiredPolicyVersion))
						{
							continue;
						}

						removePolicyVersion(policyId, version);
						numRemoved++;
					}

				}
			}

			return null;
		}

		private Path getPolicyVersionPath(String policyId,
				PolicyVersion versionId)
		{
			return getPolicyDirectory(policyId).resolve(
					versionId + policyFilenameSuffix);
		}

		@Override
		public PolicySet getPolicyVersion(String policyId, PolicyVersion version)
				throws IOException
		{
			if (policyId == null || version == null)
			{
				return null;
			}
			/*
			 * Make sure the PDP is in sync with the returned policy version
			 */
			synchronized (domainDirPath)
			{
				syncPDP();
				final File policyVersionFile = getPolicyVersionPath(policyId,
						version).toFile();
				return loadPolicy(policyVersionFile);
			}
		}

		@Override
		public PolicySet removePolicyVersion(String policyId,
				PolicyVersion version) throws IOException,
				IllegalArgumentException
		{
			if (policyId == null || version == null)
			{
				return null;
			}

			final File policyVersionFile = getPolicyVersionPath(policyId,
					version).toFile();
			final PolicySet policy;
			synchronized (domainDirPath)
			{
				/*
				 * Check whether it is not used by the PDP. First make sure the
				 * PDP is up-to-date with the repository
				 */
				syncPDP();
				final PolicyVersion requiredPolicyVersion = pdp
						.getStaticRootAndRefPolicies().get(policyId);
				if (requiredPolicyVersion != null
						&& requiredPolicyVersion.equals(version))
				{
					throw new IllegalArgumentException(
							"Policy '"
									+ policyId
									+ "' / Version "
									+ version
									+ " cannot be removed because it is still used by the PDP, either as root policy or referenced directly/indirectly by the root policy.");
				}

				policy = loadPolicy(policyVersionFile);
				// if there is no such policy version, nothing to remove
				if (policy == null)
				{
					return null;
				}

				if (policyVersionFile.delete())
				{
					// Check whether the policy directory is left empty (no more
					// version)
					final Path policyDirPath = policyVersionFile
							.getParentFile().toPath();
					try (final DirectoryStream<Path> policyDirStream = Files
							.newDirectoryStream(policyDirPath,
									policyFilePathFilter))
					{
						if (!policyDirStream.iterator().hasNext())
						{
							// policy directory left empty of versions -> remove
							// it
							FlatFileDAOUtils.deleteDirectory(policyDirPath, 0);
						}
					} catch (IOException e)
					{
						throw new IOException(
								"Error checking if policy directory is empty or removing it after removing last version: "
										+ policyDirPath);
					}
				} else
				{
					throw new IOException("Failed to delete policy file: '"
							+ policyVersionFile + "'");
				}

			}

			return policy;
		}

		@Override
		public VERSION_DAO_CLIENT getVersionDAOClient(String policyId,
				PolicyVersion version)
		{
			if (policyId == null || version == null)
			{
				return null;
			}

			return policyVersionDAOClientFactory.getInstance(policyId, version,
					this);
		}

		@Override
		public PolicyVersion getLatestPolicyVersionId(String policyId)
				throws IOException
		{
			if (policyId == null)
			{
				return null;
			}

			/*
			 * We could cache this, but this is meant to be used as a DAO in a
			 * REST API, i.e. the API should be stateless as much as possible.
			 * Therefore, we should avoid caching when performance is not
			 * critical (the performance-critical part is getPDP() only). Also
			 * this should be in sync as much as possible with the filesystem.
			 */
			/*
			 * Make sure the PDP is in sync/consistent with the info returned
			 * (last version)
			 */
			PolicyVersion latestVersion = null;
			synchronized (domainDirPath)
			{
				final Path policyDirPath = getPolicyDirectory(policyId);
				if (!Files.exists(policyDirPath)
						|| !Files.isDirectory(policyDirPath))
				{
					return null;
				}

				try (final DirectoryStream<Path> policyDirStream = Files
						.newDirectoryStream(policyDirPath, policyFilePathFilter))
				{
					for (final Path policyVersionFilePath : policyDirStream)
					{
						final String versionPlusSuffix = policyVersionFilePath
								.getFileName().toString();
						final String versionId = versionPlusSuffix.substring(0,
								versionPlusSuffix.length()
										- policyFilenameSuffix.length());
						final PolicyVersion version = new PolicyVersion(
								versionId);
						if (latestVersion == null
								|| latestVersion.compareTo(version) < 0)
						{
							latestVersion = version;
						}
					}
				} catch (IOException e)
				{
					throw new IOException(
							"Error listing policy version files in policy directory '"
									+ policyDirPath + "' of domain '"
									+ domainId + "'", e);
				}

				// Sync the PDP with info returned
				syncPDP();
			}

			return latestVersion;
		}

		/**
		 * Get policy versions from policy directory, ordered from latest to
		 * oldest
		 * 
		 * @param policyDirPath
		 * @return versions; empty if directory does not exist or is not a
		 *         directory
		 * @throws IOException
		 */
		private NavigableSet<PolicyVersion> getPolicyVersions(Path policyDirPath)
				throws IOException
		{
			assert policyDirPath != null;

			if (!Files.exists(policyDirPath)
					|| !Files.isDirectory(policyDirPath))
			{
				return EMPTY_TREE_SET;
			}

			final TreeSet<PolicyVersion> versions = new TreeSet<>(
					Collections.reverseOrder());
			try (final DirectoryStream<Path> policyDirStream = Files
					.newDirectoryStream(policyDirPath, policyFilePathFilter))
			{
				for (final Path policyVersionFilePath : policyDirStream)
				{
					final String versionPlusSuffix = policyVersionFilePath
							.getFileName().toString();
					final String versionId = versionPlusSuffix.substring(
							0,
							versionPlusSuffix.length()
									- policyFilenameSuffix.length());
					final PolicyVersion version = new PolicyVersion(versionId);
					versions.add(version);
				}
			} catch (IOException e)
			{
				throw new IOException(
						"Error listing policy version files in policy directory '"
								+ policyDirPath + "' of domain '" + domainId
								+ "'", e);
			}

			return versions;
		}

		@Override
		public NavigableSet<PolicyVersion> getPolicyVersions(String policyId)
				throws IOException
		{
			if (policyId == null)
			{
				return EMPTY_TREE_SET;
			}

			final NavigableSet<PolicyVersion> versions;
			/*
			 * We could cache this, but this is meant to be used as a DAO in a
			 * REST API, i.e. the API should be stateless as much as possible.
			 * Therefore, we should avoid caching when performance is not
			 * critical (the performance-critical part is getPDP() only). Also
			 * this should be in sync as much as possible with the filesystem.
			 */
			synchronized (domainDirPath)
			{
				final Path policyDir = getPolicyDirectory(policyId);
				versions = getPolicyVersions(policyDir);
				// make sure the current PDP state is consistent with the info
				// returned
				syncPDP();
			}

			return versions;
		}

		@Override
		public POLICY_DAO_CLIENT getPolicyDAOClient(String policyId)
		{
			if (policyId == null)
			{
				return null;
			}

			return policyDAOClientFactory.getInstance(policyId, this);
		}

		@Override
		public NavigableSet<PolicyVersion> removePolicy(String policyId)
				throws IOException, IllegalArgumentException
		{
			if (policyId == null)
			{
				return EMPTY_TREE_SET;
			}

			final PolicyVersion requiredPolicyVersion;
			final NavigableSet<PolicyVersion> versions;
			synchronized (domainDirPath)
			{
				syncPDP();
				requiredPolicyVersion = pdp.getStaticRootAndRefPolicies().get(
						policyId);
				if (requiredPolicyVersion != null)
				{
					throw new IllegalArgumentException(
							"Policy '"
									+ policyId
									+ "' cannot be removed because this policy (version "
									+ requiredPolicyVersion
									+ ") is still used by the PDP, either as root policy or referenced directly/indirectly by the root policy.");
				}

				final Path policyDir = getPolicyDirectory(policyId);
				versions = getPolicyVersions(policyDir);
				try
				{
					// if directory does not exist, this method just returns
					// right away
					FlatFileDAOUtils.deleteDirectory(policyDir, 0);
				} catch (IOException e)
				{
					throw new IOException("Error removing policy directory: "
							+ policyDir);
				}
			}

			return versions;
		}

		@Override
		public Set<String> getPolicyIDs() throws IOException
		{
			/*
			 * We could cache this, but this is meant to be used as a DAO in a
			 * REST API, i.e. the API should be as stateless as possible.
			 * Therefore, we should avoid caching when performance is not
			 * critical (the performance-critical part is getPDP() only). Also
			 * this should be in sync as much as possible with the filesystem.
			 */
			final Set<String> policyIds = new HashSet<>();
			synchronized (domainDirPath)
			{
				try (final DirectoryStream<Path> policyParentDirStream = Files
						.newDirectoryStream(policyParentDirPath,
								DIRECTORY_FILTER))
				{
					for (final Path policyDirPath : policyParentDirStream)
					{
						final String policyDirName = policyDirPath
								.getFileName().toString();
						final String policyId;
						try
						{
							policyId = FlatFileDAOUtils
									.base64UrlDecode(policyDirName);
						} catch (IllegalArgumentException e)
						{
							throw new RuntimeException(
									"Invalid policy directory name (bad encoding): "
											+ policyDirName);
						}

						policyIds.add(policyId);
					}
				} catch (IOException e)
				{
					throw new IOException(
							"Error listing files in policies directory '"
									+ policyParentDirPath + "' of domain '"
									+ domainId + "'", e);
				}

				// make sure PDP is consistent/in sync with the info returned
				syncPDP();
			}

			return policyIds;
		}

		@Override
		public ReadableDomainProperties removeDomain() throws IOException
		{
			synchronized (domainDirPath)
			{
				if (Files.exists(domainDirPath, LinkOption.NOFOLLOW_LINKS))
				{
					FlatFileDAOUtils.deleteDirectory(domainDirPath, 2);
				}

				synchronized (domainsRootDir)
				{
					removeDomainFromCache(domainId);
				}
			}

			return new ReadableDomainPropertiesImpl(domainId, null,
					cachedExternalId);
		}

		@Override
		public void close() throws IOException
		{
			// if synchronization enabled
			if (dirToMemSyncScheduler != null)
			{
				/*
				 * Code adapted from ExecutorService javadoc
				 */
				this.dirToMemSyncScheduler.shutdown(); // Disable new tasks from
														// being submitted
				try
				{
					// Wait a while for existing tasks to terminate
					if (!dirToMemSyncScheduler.awaitTermination(
							domainDirToMemSyncIntervalSec, TimeUnit.SECONDS))
					{
						LOGGER.error(
								"Domain {}: scheduler wait timeout ({}s) occurred before task could terminate after shutdown request.",
								domainDirToMemSyncIntervalSec, domainId);
						dirToMemSyncScheduler.shutdownNow(); // Cancel currently
																// executing
																// tasks
						// Wait a while for tasks to respond to being cancelled
						if (!dirToMemSyncScheduler
								.awaitTermination(
										domainDirToMemSyncIntervalSec,
										TimeUnit.SECONDS))
						{
							LOGGER.error(
									"Domain {}: scheduler wait timeout ({}s) occurred before task could terminate after shudownNow request.",
									domainId, domainDirToMemSyncIntervalSec);
						}
					}
				} catch (InterruptedException ie)
				{
					LOGGER.error(
							"Domain {}: scheduler interrupted while waiting for sync task to complete",
							domainId, ie);
					// (Re-)Cancel if current thread also interrupted
					dirToMemSyncScheduler.shutdownNow();
					// Preserve interrupt status
					Thread.currentThread().interrupt();
				}
			}

			if (pdp != null)
			{
				pdp.close();
			}
		}

	}

	/**
	 * Create domain DAO and register it in the map (incl. domainIDsByExternalId
	 * if props != null && props.getExternalId() != null)
	 * 
	 * @param domainId
	 * @param domainDirectory
	 * @param props
	 *            (optional), specific domain properties, or null if default or
	 *            no properties should be used
	 * @return domain DAO client
	 * @throws IOException
	 */
	private DOMAIN_DAO_CLIENT addDomainToCacheAfterDirectoryCreated(
			String domainId, Path domainDirectory,
			WritableDomainProperties props) throws IOException
	{
		final FlatFileBasedDomainDAO<VERSION_DAO_CLIENT, POLICY_DAO_CLIENT> domainDAO = new FileBasedDomainDAOImpl(
				domainDirectory, props);
		final DOMAIN_DAO_CLIENT domainDAOClient = domainDAOClientFactory
				.getInstance(domainId, domainDAO);
		this.domainMap.put(domainId, domainDAOClient);

		if (props != null)
		{

			// props != null
			final String domainExternalId = props.getExternalId();
			if (domainExternalId != null)
			{
				domainIDsByExternalId.put(domainExternalId, domainId);
			}
		}

		return domainDAOClient;
	}

	/**
	 * Creates instance
	 * 
	 * @param domainsRoot
	 *            root directory of the configuration data of security domains,
	 *            one subdirectory per domain
	 * @param domainTmpl
	 *            domain template directory; directories of new domains are
	 *            created from this template
	 * @param domainsSyncIntervalSec
	 *            how often (in seconds) the synchronization of managed domains
	 *            (in memory) with the domain subdirectories in the
	 *            <code>domainsRoot</code> directory (on disk) is done. If
	 *            <code>domainSyncInterval</code> > 0, every
	 *            <code>domainSyncInterval</code>, the managed domains (loaded
	 *            in memory) are updated if any change has been detected in the
	 *            <code>domainsRoot</code> directory in this interval (since
	 *            last sync). To be more specific, <i>any change</i> here means
	 *            any creation/deletion/modification of a domain folder
	 *            (modification means: any file changed within the folder). If
	 *            <code>domainSyncInterval</code> &lt;= 0, synchronization is
	 *            disabled.
	 * @param pdpModelHandler
	 *            PDP configuration model handler
	 * @param maxNumOfPoliciesPerDomain
	 *            max number of policies per domain, unlimited if negative.
	 * @param useRandomAddressBasedUUID
	 *            true iff a random multicast address must be used as node field
	 *            of generated UUIDs (Version 1), else the MAC address of one of
	 *            the network interfaces is used. Setting this to 'true' is NOT
	 *            recommended unless the host is disconnected from the network.
	 *            These generated UUIDs are used for domain IDs.
	 * @param maxNumOfVersionsPerPolicy
	 *            max number of versions per policy, unlimited if negative.
	 * @param removeOldestVersionsIfMaxExceeded
	 *            true iff oldest unused policies versions must be automatically
	 *            removed to make sure that {@code maxNumOfVersionsPerPolicy} is
	 *            not exceeded
	 * @param domainDAOClientFactory
	 *            domain DAO client factory
	 * @throws IOException
	 *             I/O error occurred scanning existing domain folders in
	 *             {@code domainsRoot} for loading; or if
	 *             {@code domainsSyncIntervalSec > 0} and a WatchService for
	 *             watching directory changes to the domains on the filesystem
	 *             could not be created
	 */
	@ConstructorProperties({ "domainsRoot", "domainTmpl",
			"domainsSyncIntervalSec", "pdpModelHandler",
			"useRandomAddressBasedUUID", "maxNumOfPoliciesPerDomain",
			"maxNumOfVersionsPerPolicy", "removeOldestVersionsIfMaxExceeded",
			"domainDAOClientFactory" })
	public FlatFileBasedDomainsDAO(
			Resource domainsRoot,
			Resource domainTmpl,
			int domainsSyncIntervalSec,
			PdpModelHandler pdpModelHandler,
			boolean useRandomAddressBasedUUID,
			int maxNumOfPoliciesPerDomain,
			int maxNumOfVersionsPerPolicy,
			boolean removeOldestVersionsIfMaxExceeded,
			DomainDAOClient.Factory<VERSION_DAO_CLIENT, POLICY_DAO_CLIENT, FlatFileBasedDomainDAO<VERSION_DAO_CLIENT, POLICY_DAO_CLIENT>, DOMAIN_DAO_CLIENT> domainDAOClientFactory)
			throws IOException
	{
		if (domainsRoot == null || domainTmpl == null
				|| pdpModelHandler == null || domainDAOClientFactory == null)
		{
			throw ILLEGAL_CONSTRUCTOR_ARGS_EXCEPTION;
		}

		this.domainDAOClientFactory = domainDAOClientFactory;
		this.policyDAOClientFactory = domainDAOClientFactory
				.getPolicyDAOClientFactory();
		this.policyVersionDAOClientFactory = policyDAOClientFactory
				.getVersionDAOClientFactory();

		this.maxNumOfPoliciesPerDomain = maxNumOfPoliciesPerDomain;
		this.maxNumOfPoliciesReachedException = new TooManyPoliciesException(
				"Max number of policies (" + maxNumOfPoliciesPerDomain
						+ ") reached for the domain");
		this.maxNumOfVersionsPerPolicy = maxNumOfVersionsPerPolicy;
		this.maxNumOfVersionsReachedException = new TooManyPoliciesException(
				"Max number of versions (" + maxNumOfVersionsPerPolicy
						+ ") reached for the policy");
		this.removeOldestVersionsIfMaxExceeded = removeOldestVersionsIfMaxExceeded;

		this.uuidGen = initUUIDGenerator(useRandomAddressBasedUUID);
		this.pdpModelHandler = pdpModelHandler;

		// Validate domainsRoot arg
		if (!domainsRoot.exists())
		{
			throw new IllegalArgumentException(
					"'domainsRoot' resource does not exist: "
							+ domainsRoot.getDescription());
		}

		final String ioExMsg = "Cannot resolve 'domainsRoot' resource '"
				+ domainsRoot.getDescription()
				+ "' as a file on the file system";
		File domainsRootFile = null;
		try
		{
			domainsRootFile = domainsRoot.getFile();
		} catch (IOException e)
		{
			throw new IllegalArgumentException(ioExMsg, e);
		}

		this.domainsRootDir = domainsRootFile.toPath();
		FlatFileDAOUtils
				.checkFile(
						"File defined by SecurityDomainManager parameter 'domainsRoot'",
						domainsRootDir, true, true);

		// Validate domainTmpl directory arg
		if (!domainTmpl.exists())
		{
			throw new IllegalArgumentException(
					"'domainTmpl' resource does not exist: "
							+ domainTmpl.getDescription());
		}

		final String ioExMsg2 = "Cannot resolve 'domainTmpl' resource '"
				+ domainTmpl.getDescription()
				+ "' as a file on the file system";
		File domainTmplFile = null;
		try
		{
			domainTmplFile = domainTmpl.getFile();
		} catch (IOException e)
		{
			throw new IllegalArgumentException(ioExMsg2, e);
		}

		this.domainTmplDirPath = domainTmplFile.toPath();
		FlatFileDAOUtils.checkFile(
				"File defined by SecurityDomainManager parameter 'domainTmpl'",
				domainTmplDirPath, true, false);

		LOGGER.debug("Looking for domain sub-directories in directory {}",
				domainsRootDir);
		try (final DirectoryStream<Path> dirStream = Files
				.newDirectoryStream(domainsRootDir))
		{
			for (final Path domainPath : dirStream)
			{
				LOGGER.debug("Checking domain in file {}", domainPath);
				if (!Files.isDirectory(domainPath))
				{
					LOGGER.warn(
							"Ignoring invalid domain file {} (not a directory)",
							domainPath);
					continue;
				}

				// domain folder name is the domain ID
				final Path lastPathSegment = domainPath.getFileName();
				if (lastPathSegment == null)
				{
					throw new RuntimeException("Invalid Domain folder '"
							+ domainPath + "': no filename");
				}

				final String domainId = lastPathSegment.toString();
				final FlatFileBasedDomainDAO<VERSION_DAO_CLIENT, POLICY_DAO_CLIENT> domainDAO;
				try
				{
					domainDAO = new FileBasedDomainDAOImpl(domainPath, null);
				} catch (IllegalArgumentException e)
				{
					throw new RuntimeException(
							"Invalid domain data for domain '" + domainId + "'",
							e);
				}

				final DOMAIN_DAO_CLIENT domain = domainDAOClientFactory
						.getInstance(domainId, domainDAO);
				domainMap.put(domainId, domain);
			}
		} catch (IOException e)
		{
			throw new IOException(
					"Failed to scan files in the domains root directory '"
							+ domainsRootDir
							+ "' looking for domain directories", e);
		}

		this.domainDirToMemSyncIntervalSec = Integer.valueOf(
				domainsSyncIntervalSec).longValue();
	}

	/**
	 * Close domains, i.e. PDPs, sync threads (to be called by Spring when
	 * application stopped)
	 */
	public void closeDomains()
	{
		synchronized (domainsRootDir)
		{
			for (final DOMAIN_DAO_CLIENT domain : domainMap.values())
			{
				try (final FlatFileBasedDomainDAO<VERSION_DAO_CLIENT, POLICY_DAO_CLIENT> domainDAO = domain
						.getDAO())
				{
					domainDAO.close();
				} catch (Throwable t)
				{
					LOGGER.error("Error closing domain {}", domain.getDAO()
							.getDomainId(), t);
				}
			}
		}
	}

	@Override
	public DOMAIN_DAO_CLIENT getDomainDAOClient(String domainId)
			throws IOException
	{
		if (domainId == null)
		{
			throw NULL_DOMAIN_ID_ARG_EXCEPTION;
		}

		final DOMAIN_DAO_CLIENT domain = domainMap.get(domainId);
		if (domain == null)
		{
			/*
			 * check whether domain directory exists (in case it is not
			 * synchronized with domain map
			 */
			final Path domainDir = this.domainsRootDir.resolve(domainId);
			/*
			 * Synchronized block two avoid that two threads adding the same
			 * desynced domain entry to the map
			 */
			synchronized (domainsRootDir)
			{
				if (Files.exists(domainDir))
				{
					return addDomainToCacheAfterDirectoryCreated(domainId,
							domainDir, null);
				}
			}
		}

		return domain;
	}

	@Override
	public String addDomain(WritableDomainProperties props) throws IOException,
			IllegalArgumentException
	{
		final UUID uuid = uuidGen.generate();
		/*
		 * Encode UUID with Base64url to have shorter IDs in REST API URL paths
		 * and to be compatible with filenames on any operating system, since
		 * the resulting domain ID is used as name for the directory where all
		 * the domain's data will be stored.
		 */
		final ByteBuffer byteBuf = ByteBuffer.wrap(new byte[16]);
		byteBuf.putLong(uuid.getMostSignificantBits());
		byteBuf.putLong(uuid.getLeastSignificantBits());
		final String domainId = FlatFileDAOUtils.base64UrlEncode(byteBuf
				.array());
		synchronized (domainsRootDir)
		{
			// this should not happen if the UUID generator can be trusted, but
			// - hey - we never
			// know.
			if (this.domainMap.containsKey(domainId))
			{
				throw new ConcurrentModificationException(
						"Generated domain ID conflicts (is same as) ID of existing domain (flawed domain UUID generator or ID generated in different way?): ID="
								+ domainId);
			}

			final Path domainDir = this.domainsRootDir.resolve(domainId);
			if (Files.notExists(domainDir))
			{
				/*
				 * Create/initialize new domain directory from domain template
				 * directory
				 */
				FlatFileDAOUtils.copyDirectory(this.domainTmplDirPath,
						domainDir, 2);
			}

			addDomainToCacheAfterDirectoryCreated(domainId, domainDir, props);
		}

		return domainId;
	}

	@Override
	public Set<String> getDomainIDs(String externalId) throws IOException
	{
		synchronized (domainsRootDir)
		{
			if (externalId != null)
			{
				// externalId not null
				final String domainId = domainIDsByExternalId.get(externalId);
				if (domainId == null)
				{
					return Collections.<String> emptySet();
				}

				// domainId not null, check if domain is still there in the
				// repository
				final Path domainDirPath = this.domainsRootDir
						.resolve(domainId);
				if (Files.exists(domainDirPath, LinkOption.NOFOLLOW_LINKS))
				{
					return Collections.<String> singleton(domainId);
				}

				// domain directory no longer exists, remove from map and so on
				removeDomainFromCache(domainId);
				return Collections.<String> emptySet();
			}

			// externalId == null
			/*
			 * All changes to domainMap are synchronized by 'domainsRootDir'. So
			 * we can iterate and change if necessary for synchronizing the
			 * domains root directory with the domainMap (Using a domainMap is
			 * necessary for quick access to domains' PDPs.)
			 */
			final Set<String> oldDomainIDs = new HashSet<>(domainMap.keySet());
			final Set<String> newDomainIDs = new HashSet<>();
			try (final DirectoryStream<Path> dirStream = Files
					.newDirectoryStream(domainsRootDir))
			{
				for (final Path domainDirPath : dirStream)
				{
					LOGGER.debug("Checking domain in file {}", domainDirPath);
					if (!Files.isDirectory(domainDirPath))
					{
						LOGGER.warn(
								"Ignoring invalid domain file {} (not a directory)",
								domainDirPath);
						continue;
					}

					// domain folder name is the domain ID
					final Path lastPathSegment = domainDirPath.getFileName();
					if (lastPathSegment == null)
					{
						throw new RuntimeException("Invalid Domain folder '"
								+ domainDirPath + "': no filename");
					}

					final String domainId = lastPathSegment.toString();
					newDomainIDs.add(domainId);
					if (oldDomainIDs.remove(domainId))
					{
						// not new domain, but directory may have changed ->
						// sync
						final DOMAIN_DAO_CLIENT domain = domainMap
								.get(domainId);
						if (domain != null)
						{
							domain.getDAO().sync();
						}
					} else
					{
						// new domain directory
						addDomainToCacheAfterDirectoryCreated(domainId,
								domainDirPath, null);
					}
				}
			} catch (IOException e)
			{
				throw new IOException(
						"Failed to scan files in the domains root directory '"
								+ domainsRootDir
								+ "' looking for domain directories", e);
			}

			if (!oldDomainIDs.isEmpty())
			{
				// old domains remaining in cache that don't match directories
				// -> removed
				// -> remove from cache
				for (final String domainId : oldDomainIDs)
				{
					removeDomainFromCache(domainId);
				}
			}

			return newDomainIDs;
		}
	}

	@Override
	public boolean containsDomain(String domainId)
	{
		if (domainId == null)
		{
			throw NULL_DOMAIN_ID_ARG_EXCEPTION;
		}

		return domainMap.containsKey(domainId);
	}

}