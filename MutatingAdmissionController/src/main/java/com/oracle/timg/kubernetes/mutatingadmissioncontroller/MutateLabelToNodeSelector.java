
package com.oracle.timg.kubernetes.mutatingadmissioncontroller;

import java.io.StringWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.metrics.annotation.Counted;

import io.helidon.config.Config;
import io.helidon.config.Config.Type;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonPatch;
import jakarta.json.JsonPatchBuilder;
import jakarta.json.JsonValue;
import jakarta.json.JsonValue.ValueType;
import jakarta.json.JsonWriter;
import jakarta.json.JsonWriterFactory;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.stream.JsonGenerator;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.java.Log;

/**
 * Mutates incoming requests looking for
 */
@Path("/mutate")
@ApplicationScoped
@Counted
@Log
public class MutateLabelToNodeSelector {

	private static final String ARRAY_CONFIG_ELEMENT_TYPE_CONFIG = "arrayElementConfigType";
	private static final String ARRAY_VALUE_CONFIG = "arrayValue";
	private static final String ARRAY_KEY_VALUE_CONFIG = "arrayKeyValue";
	private static final String ARRAY_KEY_VALUE_NO_MATCH_ACTION_CONFIG = "arrayKeyValueNoMatchAction";
	private static final String ARRAY_INDEX_CONFIG = "arrayIndex";
	private static final String ARRAY_INDEX_MISSING_ERROR_CONFIG = "arrayIndexMissingError";
	private static final String ARRAY_KEY_CONFIG = "arrayKey";
	private static final String ARRAY_KEY_MISSING_ERROR_CONFIG = "arrayKeyMissingError";
	private static final String LABELS_OBJECT = "labels";
	private static final String METADATA_OBJECT = "metadata";
	public final static String API_VERSION_FIELD = "apiVersion";
	public final static String KIND_FIELD = "kind";

	public final static String REQUEST_OBJECT = "request";
	public final static String UID_FIELD = "uid";
	public final static String NAME_FIELD = "name";
	public final static String NAMESPACE_FIELD = "namespace";
	public final static String OPERATION_FIELD = "operation";
	public final static String OBJECT_OBJECT = "object";
	public final static String OLD_OBJECT_OBJECT = "oldObject";

	public final static String CREATE_OPERATION = "CREATE";
	public final static String MODIFY_OPERATION = "MODIFY";

	private final Set<String> targetNamespaces;
	private final String inputLabelName;
	private final boolean requireMapping;
	private final boolean errorOnMissingMapping;
	private final Map<String, Config> mappingsConfig;

	@Inject
	public MutateLabelToNodeSelector(
			@ConfigProperty(name = "mutate.targetNamespaces", defaultValue = "") String targetNamespacesConfig,
			@ConfigProperty(name = "mutate.input.labelName", defaultValue = "targetMapping") String inputLabelName,
			@ConfigProperty(name = "mutate.input.requireMapping", defaultValue = "false") boolean requireMapping,
			@ConfigProperty(name = "mutate.input.errorOnMissingMapping", defaultValue = "true") boolean errorOnMissingMapping,
			Config config) {
		this.targetNamespaces = Arrays.stream(targetNamespacesConfig.split(",")).map(namespace -> namespace.trim())
				.filter(name -> !name.equals("NONE")).collect(Collectors.toSet());
		log.info("Targeting namespaces " + this.targetNamespaces + " based on input string " + targetNamespacesConfig);
		this.inputLabelName = inputLabelName;
		this.requireMapping = requireMapping;
		this.errorOnMissingMapping = errorOnMissingMapping;
		log.info("Will look for for specified label of " + this.inputLabelName);
		// dumpConfigNames(config, "Root");
		Config mutateConfigSection = config.get("mutate");
		dumpConfigNames(mutateConfigSection, "mutate");
		Config mappingsConfigSection = mutateConfigSection.get("mappings");
		log.info("Mutate -> mappings tree is \n" + dumpConfigTree(mappingsConfigSection, 0, false));
		this.mappingsConfig = mappingsConfigSection.asNodeList().get().stream()
				.collect(Collectors.toMap(mapping -> mapping.name(), mapping -> mapping));
		log.info("Will use the following labels for mappings" + this.mappingsConfig.keySet());
	}

	private String dumpConfigTree(Config config, int indent, boolean hyphenStart) {
		String resp = "";
		// setup the indent
		int realIndent = indent;
		if (hyphenStart) {
			realIndent = indent - 2;
		}
		for (int spaceCount = 0; spaceCount < realIndent; spaceCount++) {
			resp += " ";
		}
		if (hyphenStart) {
			resp += "- ";
		}
		// output our name
		resp += config.name() + ": (" + config.type() + ")";
		// leaf and value should be the same
		if (config.isLeaf() || config.type() == Type.VALUE) {
			resp = resp + ": ";
			if (config.hasValue()) {
				resp += config.asString().get();
			}
			resp += "\n";
		} else if (config.type() == Type.OBJECT) {
			resp += "\n";
			resp += config.asNodeList().get().stream()
					.map(subConfigNode -> dumpConfigTree(subConfigNode, indent + 2, false))
					.collect(Collectors.joining());
		} else if (config.type() == Type.LIST) {
			resp += "\n";
			// put the open spacing in
			for (int spaceCount = 0; spaceCount < realIndent; spaceCount++) {
				resp += " ";
			}
			resp += "[\n";
			resp += config.asNodeList().get().stream()
					.map(subConfigNode -> dumpConfigTree(subConfigNode, indent + 2, true))
					.collect(Collectors.joining());
			// put the close spacing in
			for (int spaceCount = 0; spaceCount < realIndent; spaceCount++) {
				resp += " ";
			}
			resp += "]\n";

		}
		return resp;
	}

	private void dumpConfigNames(Config config, String nodeName) {
		if (config.exists()) {
			log.info("Config NodeName " + nodeName + " exists, scaning contents");
			config.asNodeList().get().stream()
					.forEach(configNode -> log.info("Found sub config node named " + configNode.name()));
		} else {
			log.info("NodeName " + nodeName + " does not exist");
		}
	}

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public String testEndpoint() {
		return "This is the test endpoint";
	}

	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public AdmissionRequestResponse mutateAdmissionRequest(JsonObject incommingRequest) {
		if (incommingRequest == null) {
			log.info("Inbound request is null, can't proceed");
			return null;

		}
		log.info("Inbound object is\n" + jsonPrettyPrint(incommingRequest));
		String apiVersion = incommingRequest.getString(API_VERSION_FIELD);
		String admissionRequestKind = incommingRequest.getString(KIND_FIELD);
		// locate the UID
		JsonObject request = incommingRequest.getJsonObject(REQUEST_OBJECT);
		String requestUid = request.getString(UID_FIELD);
		String requestNamespace = request.getString(NAMESPACE_FIELD);
		String requestOperationString = request.getString(OPERATION_FIELD).toUpperCase();
		KubernetesOperationMode requestOperation = KubernetesOperationMode.valueOf(requestOperationString);
		boolean allowed = true;
		JsonPatchBuilder patchBuilder = Json.createPatchBuilder();
		// create the basic response here
		AdmissionResponseData responseData = AdmissionResponseData.builder().uid(requestUid).allowed(allowed).build()
				.addPatches(patchBuilder.build());
		AdmissionRequestResponse response = AdmissionRequestResponse.builder().apiVersion(apiVersion)
				.kind(admissionRequestKind).response(responseData).build();
		Jsonb jsonb = JsonbBuilder.create(new JsonbConfig().withFormatting(true));
		log.fine("Empty Response is\n" + jsonb.toJson(response));
		if (mappingsConfig.size() == 0) {
			log.info("no mappings in the config, just returned request");
			return response;
		}
		if (requestOperation == null) {
			log.warning("Incomming request " + incommingRequest + " has an unknown operation type "
					+ requestOperationString + " will not process");
			// return a response with no patches
			return response;
		}
		if ((requestOperation == KubernetesOperationMode.CREATE)
				|| (requestOperation == KubernetesOperationMode.UPDATE)) {
			log.info(
					"Incomming request has an operation of " + requestOperation + " this is something we will process");
		} else {
			log.info("Incomming request has an operation of " + requestOperation
					+ " this is something we won't process");
			// return a response with no patches
			return response;
		}
		if (targetNamespaces.contains(requestNamespace)) {
			// it's a namespace we care about
			log.info("Incomming request targets a namespace " + requestNamespace
					+ " which is in our set of targeted namespaces (" + targetNamespaces + ") will process this");
		} else {
			log.info("Incomming request targets a namespace " + requestNamespace
					+ " which is not in our set of targeted namespaces (" + targetNamespaces + ") will not process");
			// return a response with no patches
			return response;
		}

		String requestName = request.getString(NAME_FIELD);
		JsonObject requestObject = request.getJsonObject(OBJECT_OBJECT);
		String requestKind = requestObject.getString(KIND_FIELD);
		JsonObject metaData = requestObject.getJsonObject(METADATA_OBJECT);
		JsonObject labels = metaData.getJsonObject(LABELS_OBJECT);
		if (labels == null) {
			String msg = "Can't locate " + METADATA_OBJECT + " -> " + LABELS_OBJECT;
			if (requireMapping) {
				msg += ", cannot proceed and rejecting request";
				log.warning(msg);
				response.getStatus().setCode(400);
				response.getStatus().setMessage(msg);
				return response;
			} else {
				msg += ", will not process this request";
				log.info(msg);
				return response;
			}
		}
		// is there an annotation with the name in the config file ? If so we can
		// process it
		String inputLabel;
		if (labels.containsKey(inputLabelName)) {
			inputLabel = labels.getString(inputLabelName);
			log.info("Deployment " + requestName + " of type " + requestKind + " has an label for " + inputLabelName
					+ " with value " + inputLabel + " continuing");
		} else {
			String msg = "Deployment " + requestName + " does not contain a label for " + inputLabelName;
			if (requireMapping) {
				msg += ", cannot proceed and rejecting request";
				log.warning(msg);
				response.getStatus().setCode(400);
				response.getStatus().setMessage(msg);
				return response;
			} else {
				msg += ", will not process this request";
				log.info(msg);
				return response;
			}
		}
		// do we know how to handle this label ?
		Config selectedMappingConfig = mappingsConfig.get(inputLabel);
		if (selectedMappingConfig == null) {
			String msg = "No mapping config found for mapping " + inputLabel;
			if (errorOnMissingMapping) {
				msg += ", cannot proceed and rejecting request";
				log.warning(msg);
				response.getStatus().setCode(400);
				response.getStatus().setMessage(msg);
				return response;
			} else {
				msg += ", will not process this request";
				log.info(msg);
				return response;
			}
		} else {
			log.info("Located mapping config for mapping " + inputLabel + " will process this request");
		}
		log.info("Selected mapping is\n" + dumpConfigTree(selectedMappingConfig, 0, false));
		// for each of the objects in the config tree try to process it
		List<Config> nodes = selectedMappingConfig.asNodeList().get();
		for (Config node : nodes) {
			try {
				processMappings(requestObject, node, selectedMappingConfig, patchBuilder, "");
			} catch (MutationAdmissionControllerException e) {
				log.warning("Encountered error processing request\n" + e.getLocalizedMessage());
				response.getStatus().setCode(400);
				response.getStatus().setMessage(e.getLocalizedMessage());
				return response;
			}
		}
		JsonPatch patch = patchBuilder.build();
		log.info("Resulting patch is " + jsonPrettyPrint(patch.toJsonArray()));
		// try to apply the patch to the json in the request
		JsonObject updatedRequestObject = patch.apply(requestObject);
		log.fine("Updated request after patch is \n" + jsonPrettyPrint(updatedRequestObject));
		// add this to the response
		responseData.addPatches(patch);
		// return the response
		return response;
	}

	private void processMappings(JsonValue requestValue, Config selectedMappingConfig, Config parentMappingConfig,
			JsonPatchBuilder patchBuilder, String jsonPathStringPrefix) throws MutationAdmissionControllerException {
		Type type = selectedMappingConfig.type();
		switch (type) {
		case LIST:
			processListMappings(requestValue, selectedMappingConfig, parentMappingConfig, patchBuilder,
					jsonPathStringPrefix);
			break;
		case OBJECT:
		case VALUE:
			processObjectMappings(requestValue, selectedMappingConfig, parentMappingConfig, patchBuilder,
					jsonPathStringPrefix);
			break;
//		case VALUE:
//			processValueMappings(requestValue, selectedMappingConfig, parentMappingConfig, patchBuilder,
//					jsonPathStringPrefix);
//			break;
		case MISSING:
		default:
			log.warning("For config node " + selectedMappingConfig.key() + " has unsupported config type of "
					+ selectedMappingConfig.type() + " cannot process it");
			break;

		}
	}

	private void processObjectMappings(JsonValue requestValue, Config selectedMappingConfig, Config parentMappingConfig,
			JsonPatchBuilder patchBuilder, String jsonPathStringPrefix) throws MutationAdmissionControllerException {
		// this is an end value, the only question is if there is a matching request
		// value
		String configName = selectedMappingConfig.name();
		String configKey = selectedMappingConfig.key().toString();
		String jsonPathString = jsonPathStringPrefix + "/" + configName;
		JsonObject requestObject = requestValue == null ? null : requestValue.asJsonObject();
		JsonValue requestSubValue = requestObject == null ? null : requestObject.get(configName);
		// if this is a leaf then just process it,
		if (selectedMappingConfig.isLeaf()) {
			// if there is an equivalent in the JSON then replace, otherwise add
			if (requestSubValue == null) {
				log.finer("Json input path " + jsonPathString + " does not exist for config leaf  " + configKey
						+ " will add value");
				patchBuilder.add(jsonPathString, getJsonValue(selectedMappingConfig));
			} else {
				log.finer("Json input path " + jsonPathString + " exists for config leaf" + configKey
						+ " will add replace");
				patchBuilder.replace(jsonPathString, getJsonValue(selectedMappingConfig));
			}
		} else {
			if (requestSubValue == null) {
				log.fine("Json input path " + jsonPathString + " config " + configKey
						+ " is an object, no equivalent object in the json input will add");
				patchBuilder.add(jsonPathString, getJsonValue(selectedMappingConfig));
			} else {
				ValueType type = requestSubValue.getValueType();
				if (type != ValueType.OBJECT) {
					String msg = "Json input path " + jsonPathString + " config " + configKey
							+ " is an object, equivalent object in the json input is of type " + type
							+ " and is not a matching type, cannot proceed";
					log.info(msg);
					throw new MutatingAdmissionControllerTypeMismatchException(msg);
				}
				log.fine("Json input path " + jsonPathString + " config " + configKey
						+ " is an object, equivalent object in the json input exists will process");
				List<Config> subConfigNodes = selectedMappingConfig.asNodeList().get();
				for (Config subConfigNode : subConfigNodes) {
					processMappings(requestSubValue, subConfigNode, selectedMappingConfig, patchBuilder,
							jsonPathString);
				}
			}
		}
		return;
	}

	private void processListMappings(JsonValue requestValue, Config selectedMappingConfig, Config parentMappingConfig,
			JsonPatchBuilder patchBuilder, String jsonPathStringPrefix) throws MutationAdmissionControllerException {
		// this is an end value, the only question is if there is a matching request
		// value
		String configName = selectedMappingConfig.name();
		String configKey = selectedMappingConfig.key().toString();
		String jsonPathString = jsonPathStringPrefix + "/" + configName;
		JsonObject requestObject = requestValue == null ? null : requestValue.asJsonObject();
		JsonValue requestSubValue = requestObject == null ? null : requestObject.get(configName);
		if (requestValue == null) {
			log.fine("Json input path " + jsonPathString + " config input " + configName
					+ " is a list, no equivalent object in the json input, will add");
			patchBuilder.add(jsonPathString, getJsonValue(selectedMappingConfig));
		} else {
			ValueType type = requestSubValue.getValueType();
			if (type != ValueType.ARRAY) {
				String msg = "Json input path " + jsonPathString + " config input " + configKey
						+ " is a list, equivalent object in the json input is of type " + type
						+ " and is not a matching type, cannot proceed";
				log.info(msg);
				throw new MutatingAdmissionControllerTypeMismatchException(msg);
			}
			JsonArray configJson = requestSubValue.asJsonArray();
			// OK we have a list
			log.fine("Json input path " + jsonPathString + " config " + configKey
					+ " is a list, equivalent object in the json input is a " + type + " will process");
			List<Config> subConfigNodes = selectedMappingConfig.asNodeList().get();
			for (Config subConfigNode : subConfigNodes) {
				processListEntryToExistingJsonList(configJson, subConfigNode, selectedMappingConfig, patchBuilder,
						jsonPathString);
			}
		}
		return;

	}

	private void processListEntryToExistingJsonList(JsonArray requestArray, Config selectedMappingConfig,
			Config parentMappingConfig, JsonPatchBuilder patchBuilder, String jsonPathStringPrefix)
			throws MutationAdmissionControllerException {
		String configKey = selectedMappingConfig.key().toString();
		ConfigArrayElementLocateType locateType;
		try {
			if (selectedMappingConfig.get(ARRAY_CONFIG_ELEMENT_TYPE_CONFIG).exists()) {
				locateType = ConfigArrayElementLocateType.valueOf(
						selectedMappingConfig.get(ARRAY_CONFIG_ELEMENT_TYPE_CONFIG).asString().get().toUpperCase());
			} else {
				String msg = "Config " + configKey + "cannot locate the required field "
						+ ARRAY_CONFIG_ELEMENT_TYPE_CONFIG;
				log.warning(msg);
				throw new MutatingAdmissionControllerListConfigMissingLocateTypeException(msg);
			}
		} catch (IllegalArgumentException e) {
			String msg = "Config " + configKey + " the value of " + ARRAY_CONFIG_ELEMENT_TYPE_CONFIG
					+ " when mapped to upper case is "
					+ selectedMappingConfig.get(ARRAY_CONFIG_ELEMENT_TYPE_CONFIG).asString().get().toUpperCase()
					+ " which not a known action (Available actions are " + Arrays.stream(MissingMatchAction.values())
							.map(enumValue -> enumValue.toString()).collect(Collectors.joining(","))
					+ ")";
			log.warning(msg);
			throw new MutatingAdmissionControllerListConfigInvalidArrayKeyValueMissingException(msg);
		}
		switch (locateType) {
		case INDEX:
			processListIndexEntryToExistingJsonList(requestArray, selectedMappingConfig, parentMappingConfig,
					patchBuilder, jsonPathStringPrefix);
			break;
		case KEY:
			processListKeyEntryToExistingJsonList(requestArray, selectedMappingConfig, parentMappingConfig,
					patchBuilder, jsonPathStringPrefix);
			break;
		default:
			String msg = "Invalid locate type " + locateType
					+ ", this is a programming problem with the switch statement";
			log.severe(msg);
			throw new MutationAdmissionControllerException(msg);
		}
	}

	private void processListIndexEntryToExistingJsonList(JsonArray requestArray, Config selectedMappingConfig,
			Config parentMappingConfig, JsonPatchBuilder patchBuilder, String jsonPathStringPrefix)
			throws MutationAdmissionControllerException {
		String configKey = selectedMappingConfig.key().toString();
		Config arrayIndexValue = selectedMappingConfig.get(ARRAY_INDEX_CONFIG);
		Config arrayValue = selectedMappingConfig.get(ARRAY_VALUE_CONFIG);
		if (!(arrayIndexValue.exists() && arrayValue.exists())) {
			String msg = "Json input path prefix " + jsonPathStringPrefix + " config " + configKey
					+ " is in an array, yet at least one of " + ARRAY_INDEX_CONFIG + ", or " + ARRAY_VALUE_CONFIG
					+ " is missing, this means that the array item in the json input array cannot be located or created";
			log.info(msg);
			throw new MutatingAdmissionControllerListConfigMissingFieldsException(msg);

		}
		Integer arrayIndex = arrayIndexValue.asInt().get();
		if (arrayIndex < 0) {
			String msg = "Config " + configKey + " the value of " + ARRAY_INDEX_CONFIG + "cannot be negative";
			log.warning(msg);
			throw new MutatingAdmissionControllerListArrayIndexInvalidException(msg);
		}
		MissingMatchAction missingMatchAction;
		try {
			if (selectedMappingConfig.get(ARRAY_INDEX_MISSING_ERROR_CONFIG).exists()) {
				missingMatchAction = MissingMatchAction.valueOf(
						selectedMappingConfig.get(ARRAY_INDEX_MISSING_ERROR_CONFIG).asString().get().toUpperCase());
			} else {
				missingMatchAction = MissingMatchAction.IGNORE;
			}
		} catch (IllegalArgumentException e) {
			String msg = "Config " + configKey + " the value of " + ARRAY_INDEX_MISSING_ERROR_CONFIG
					+ " when mapped to upper case is "
					+ selectedMappingConfig.get(ARRAY_INDEX_MISSING_ERROR_CONFIG).asString().get().toUpperCase()
					+ " which not a known action (Available actions are " + Arrays.stream(MissingMatchAction.values())
							.map(enumValue -> enumValue.toString()).collect(Collectors.joining(","))
					+ ")";
			log.warning(msg);
			throw new MutatingAdmissionControllerListConfigInvalidArrayKeyValueMissingException(msg);
		}
		log.finer("Json input path prefix " + jsonPathStringPrefix + " config " + configKey
				+ " locating array entry by index of " + arrayIndex);

		if (arrayIndex >= requestArray.size()) {
			String msg = "Json input path prefix " + jsonPathStringPrefix + " config " + configKey
					+ " Specified inted in " + ARRAY_INDEX_CONFIG + " is larger than the JSON array of "
					+ requestArray.size() + ARRAY_INDEX_MISSING_ERROR_CONFIG + " is " + missingMatchAction;
			if (missingMatchAction == MissingMatchAction.ADD) {
				msg += " will create a new item and add to the end of the array";
				log.finer(msg);
				String jsonPathString = jsonPathStringPrefix + "/-";
				patchBuilder.add(jsonPathString, getJsonListValue(selectedMappingConfig));
			} else if (missingMatchAction == MissingMatchAction.ERROR) {
				msg += " erroring";
				log.info(msg);
				throw new MutatingAdmissionControllerListArrayIndexOutOfBoundsException(msg);
			} else {
				msg += " ignoring";
				log.info(msg);
			}
		} else {
			// get the JSON item specified
			JsonValue jsonValue = requestArray.get(arrayIndex);
			// the JSON path will use the index
			String jsonPathString = jsonPathStringPrefix + "/" + arrayIndex;
			// by definition to have got here we know that the object exists with a matchign
			// key / key value
			// so just process the config values, have to do these one by one though
			List<Config> nodes = arrayValue.asNodeList().get();
			for (Config node : nodes) {
				processMappings(jsonValue, node, selectedMappingConfig, patchBuilder, jsonPathString);
			}
		}
	}

	/**
	 * config item we are going to assume that it's actually a definition of the
	 * object in the json to modify, this means it will have an arrayKey,
	 * arrayKeyValue and arrayValue. The first matching object in the array will be
	 * found using the arrayKey will identify the name of the field in the json
	 * object to look at when finding that json sub object, and the arrayKeyValue
	 * will be the value, for example arrayLay = name and arrayKeyValue = nginx
	 * meant to look through the objects in the config list and locate the first
	 * which has a key of name with a value of nginx. For now these are assumed to
	 * be strings.
	 * 
	 * Once the sub object has been found then use the arrayValue config item to
	 * start processing from that object
	 * 
	 * If no object in the config array is found with that value then we create a
	 * new JSON object and add it TO THE END of the input list - the code that
	 * creates that new object will extract the arrayKey / arrayKeyValue and add
	 * them into the new object
	 */
	private void processListKeyEntryToExistingJsonList(JsonArray requestArray, Config selectedMappingConfig,
			Config parentMappingConfig, JsonPatchBuilder patchBuilder, String jsonPathStringPrefix)
			throws MutationAdmissionControllerException {
		String configKey = selectedMappingConfig.key().toString();
		Config arrayKey = selectedMappingConfig.get(ARRAY_KEY_CONFIG);
		boolean arrayKeyMissingError = selectedMappingConfig.get(ARRAY_KEY_MISSING_ERROR_CONFIG).exists()
				? selectedMappingConfig.get(ARRAY_KEY_MISSING_ERROR_CONFIG).asBoolean().get()
				: false;
		Config arrayKeyValue = selectedMappingConfig.get(ARRAY_KEY_VALUE_CONFIG);
		Config arrayValue = selectedMappingConfig.get(ARRAY_VALUE_CONFIG);
		MissingMatchAction missingMatchAction;
		try {
			if (selectedMappingConfig.get(ARRAY_KEY_VALUE_NO_MATCH_ACTION_CONFIG).exists()) {
				missingMatchAction = MissingMatchAction.valueOf(selectedMappingConfig
						.get(ARRAY_KEY_VALUE_NO_MATCH_ACTION_CONFIG).asString().get().toUpperCase());
			} else {
				missingMatchAction = MissingMatchAction.IGNORE;
			}
		} catch (IllegalArgumentException e) {
			String msg = "Config " + configKey + " the value of " + ARRAY_KEY_VALUE_NO_MATCH_ACTION_CONFIG
					+ " when mapped to upper case is "
					+ selectedMappingConfig.get(ARRAY_KEY_VALUE_NO_MATCH_ACTION_CONFIG).asString().get().toUpperCase()
					+ " which not a known action (Available actions are " + Arrays.stream(MissingMatchAction.values())
							.map(enumValue -> enumValue.toString()).collect(Collectors.joining(","))
					+ ")";
			log.warning(msg);
			throw new MutatingAdmissionControllerListConfigInvalidArrayKeyValueMissingException(msg);
		}
		// if any do now exist then
		if (!(arrayKey.exists() && arrayKeyValue.exists() && arrayValue.exists())) {
			String msg = "Json input path prefix " + jsonPathStringPrefix + " config " + configKey
					+ " is in an array, yet it does not define at least one of " + ARRAY_KEY_CONFIG + ", "
					+ ARRAY_KEY_VALUE_CONFIG + ", or " + ARRAY_VALUE_CONFIG
					+ " this means that the matching item in the json input array cannot be located or created";
			log.info(msg);
			throw new MutatingAdmissionControllerListConfigMissingFieldsException(msg);

		}
		// OK, we know this has the fields we want
		String key = arrayKey.asString().get();
		String keyValue = arrayKeyValue.asString().get();
		log.finer("Json input path prefix " + jsonPathStringPrefix + " config " + configKey
				+ " locating array entry by key where " + key + ":" + keyValue);
		Integer arrayIndex = locateJsonArrayIndex(requestArray, key, keyValue, jsonPathStringPrefix,
				arrayKeyMissingError);
		if (arrayIndex == null) {
			String msg = "Json input path prefix " + jsonPathStringPrefix + " config " + configKey
					+ " Could not locate an item in the json input array with fields " + key + ":" + keyValue + " "
					+ ARRAY_KEY_VALUE_NO_MATCH_ACTION_CONFIG + " is " + missingMatchAction;
			if (missingMatchAction == MissingMatchAction.ADD) {
				msg += " will create a new item and add to the end of the array";
				log.finer(msg);
				String jsonPathString = jsonPathStringPrefix + "/-";
				patchBuilder.add(jsonPathString, getJsonListValue(selectedMappingConfig));
			} else if (missingMatchAction == MissingMatchAction.ERROR) {
				msg += " erroring";
				log.info(msg);
				throw new MutatingAdmissionControllerListArrayKeyValueMissingException(msg);
			} else {
				msg += " ignoring";
				log.info(msg);
			}
		} else {
			// one was found, get the JSON item concerned
			JsonValue jsonValue = requestArray.get(arrayIndex);
			// the JSON path will use the index
			String jsonPathString = jsonPathStringPrefix + "/" + arrayIndex;
			// by definition to have got here we know that the object exists with a matchign
			// key / key value
			// so just process the config values, have to do these one by one though
			List<Config> nodes = arrayValue.asNodeList().get();
			for (Config node : nodes) {
				processMappings(jsonValue, node, selectedMappingConfig, patchBuilder, jsonPathString);
			}
		}
	}

	private Integer locateJsonArrayIndex(JsonArray requestArray, String key, String keyValue, String jsonPathString,
			boolean arrayKeyMissingError) throws MutatingAdmissionControllerListException {
		for (int i = 0; i < requestArray.size(); i++) {
			log.fine(
					"Searching json array " + jsonPathString + ", looking for array item with " + key + ":" + keyValue);
			JsonValue potentialIndexedObject = requestArray.get(i);
			ValueType type = potentialIndexedObject.getValueType();
			if (type != ValueType.OBJECT) {
				String msg = "Scanning input array " + jsonPathString + " to locate object with  " + key + ":"
						+ keyValue + " but array item " + i + " is not an object, but instead is of type " + type
						+ " can't handle this";
				log.info(msg);
				throw new MutatingAdmissionControllerListJsonNotAnObjectException(msg);
			}
			log.finer("Json item at index " + i + " is an object");
			JsonObject indexedObject = potentialIndexedObject.asJsonObject();
			// does this object have the key field ?
			if (!indexedObject.containsKey(key)) {
				String msg = "In json array input " + jsonPathString + "Json item at index " + i
						+ " is an object but does not have a key " + key;
				if (arrayKeyMissingError) {
					msg += ", arrayKeyMissingError is true, throwing an error";
					log.info(msg);
					throw new MutatingAdmissionControllerListArrayKeyMissingException(msg);
				} else {
					log.finer("In json array input " + jsonPathString + "Json item at index " + i
							+ " is an object but does not have a key " + key + " ignoring this item");
					continue;
				}
			}
			// it has the key field, is the key a string
			JsonValue potentialKeyValue = indexedObject.get(key);
			ValueType potentialKeyValueType = potentialKeyValue.getValueType();
			if (potentialKeyValueType != ValueType.STRING) {
				log.finer("In json array input " + jsonPathString + "Json item at index " + i
						+ " is an object and has a key " + key + " but the type of the key field is "
						+ potentialKeyValueType + " not " + ValueType.STRING + " ignoring this item");
				continue;
			}
			// ok it's a string, is it the one we want ?
			String potentialKeyValueAsString = indexedObject.getString(key);
			if (keyValue.equals(potentialKeyValueAsString)) {
				// yes it does, return the id
				return i;
			} else {
				log.finer("In json array input " + jsonPathString + "Json item at index " + i
						+ " is an object and has a key " + key + " but the value of the key field is "
						+ potentialKeyValueAsString + " and we need " + keyValue + " continuing to search");
				continue;
			}
		}
		// didn't find it
		return null;
	}

	private void processValueMappings(JsonValue requestValue, Config selectedMappingConfig, Config parentMappingConfig,
			JsonPatchBuilder patchBuilder, String jsonPathStringPrefix) throws MutationAdmissionControllerException {
		// this is an end value, the only question is if there is a matching request
		// value
		String configName = selectedMappingConfig.name();
		String configKey = selectedMappingConfig.key().toString();
		String jsonPathString = jsonPathStringPrefix + "/" + configName;
		if (requestValue == null) {
			log.fine("Json input path prefix " + jsonPathString + " config " + configKey
					+ " is a leaf object, no equivalent object in the json input  will add");
			patchBuilder.add(jsonPathString, getJsonValue(selectedMappingConfig));
		} else {
			ValueType type = requestValue.getValueType();
			if ((type == ValueType.STRING) || (type == ValueType.NUMBER) || (type == ValueType.NULL)) {
				log.fine("Json input path prefix " + jsonPathString + " config " + configKey
						+ " is a leaf, equivalent object in the jdon input  is of type " + type + " will replace");
				patchBuilder.replace(jsonPathString, getJsonValue(selectedMappingConfig));
			} else {
				String msg = "Json input path prefix " + jsonPathString + " config " + configKey
						+ " is a leaf, equivalent object in the json input is of type " + type
						+ " and is not a matching type, cannot proceed";
				log.info(msg);
				throw new MutatingAdmissionControllerTypeMismatchException(msg);
			}
		}
		return;
	}

	private JsonValue getJsonValue(Config selectedMappingConfig) throws MutationAdmissionControllerException {
		String configNodeName = selectedMappingConfig.key().toString();
		log.fine("Processing config node" + configNodeName);
		JsonValue builtObject = null;
		if (selectedMappingConfig.type() == Type.OBJECT) {
			log.finer("Config value for " + configNodeName + " is an object");
			JsonObjectBuilder builder = Json.createObjectBuilder();
			List<Config> nodes = selectedMappingConfig.asNodeList().get();
			for (Config node : nodes) {
				builder.add(node.name(), getJsonValue(node));
			}
			builtObject = builder.build();
		} else if (selectedMappingConfig.type() == Type.LIST) {
			log.finer("Config value for " + configNodeName + " is a list");
			JsonArrayBuilder builder = Json.createArrayBuilder();
			List<Config> nodes = selectedMappingConfig.asNodeList().get();
			for (Config node : nodes) {
				builder.add(getJsonListValue(node));
			}
			builtObject = builder.build();
		} else if (selectedMappingConfig.type() == Type.VALUE) {
			String selectedMappingValueString = selectedMappingConfig.asString().orElse(null);
			if (selectedMappingValueString == null) {
				log.finer("Config value for " + configNodeName + " is null, returning null");
				builtObject = JsonValue.NULL;
			} else {
				log.finer("Config " + selectedMappingConfig.name() + " has value " + selectedMappingValueString);
				// the config tree doesn't have any accessible info as to the actual type of
				// data it holds
				// we can get this as a string though in most cases, so let's try processing
				// that
				// we will do the following precidence
				// integer, long, double then default to string which will cover strings as well
				// as booleans in string form
				if (builtObject == null) {
					try {
						int v = Integer.parseInt(selectedMappingValueString);
						// it parsed
						builtObject = Json.createValue(v);
						log.finer("Config node " + configNodeName + ", Treating value " + selectedMappingValueString
								+ " as an int");
					} catch (NumberFormatException nfe) {
						// it's not an int, that's fine
					}
				}
				if (builtObject == null) {
					try {
						long v = Long.parseLong(selectedMappingValueString);
						// it parsed
						builtObject = Json.createValue(v);
						log.finer("Config node " + configNodeName + ", reating value " + selectedMappingValueString
								+ " as a long");
					} catch (NumberFormatException nfe) {
						// it's not a long, that's fine
					}
				}
				if (builtObject == null) {
					try {
						double v = Double.parseDouble(selectedMappingValueString);
						// it parsed
						builtObject = Json.createValue(v);
						log.finer("Config node " + configNodeName + ", reating value " + selectedMappingValueString
								+ " as a double");
					} catch (NumberFormatException nfe) {
						// it's not a double, that's fine
					}
				}
				if (builtObject == null) {
					// if it's true / false use that, note that this uses strict true / false (but
					// is case insensitive) though yes / no are allowed in YAML
					// (and thus in the Helidon config system) they are not allowed here.
					if ((selectedMappingValueString.equalsIgnoreCase(Boolean.TRUE.toString())
							|| selectedMappingValueString.equalsIgnoreCase(Boolean.FALSE.toString()))) {
						boolean v = Boolean.parseBoolean(selectedMappingValueString);
						builtObject = v ? JsonValue.TRUE : JsonValue.FALSE;
						log.finer("Config node " + configNodeName + ", reating value " + selectedMappingValueString
								+ " as boolean");
					} else {
						// it's a general string option
						builtObject = Json.createValue(selectedMappingValueString);
						log.finer("Config node " + configNodeName + ", reating value " + selectedMappingValueString
								+ " as a string");
					}
				}
			}
		} else {
			String msg = "Config object " + configNodeName + " is type " + selectedMappingConfig.type()
					+ " which is an unsupported input type ";
			log.info(msg);
			return Json.createValue(msg);
		}
		log.fine("Returning " + builtObject);
		return builtObject;
	}

	private JsonValue getJsonListValue(Config configListElement) throws MutationAdmissionControllerException {
		switch (configListElement.type()) {
		case LIST:
			// it's a list of lists
			return getJsonValue(configListElement);
		case OBJECT:
			return getJsonListObject(configListElement);
		case VALUE:
			// not sure that a value is allowed here, as I think that lists are of either
			// other lists or objects, but let's allow for it
			return getJsonValue(configListElement);
		case MISSING:
		default:
			String configKey = configListElement.key().toString();
			String msg = "Config " + configKey + " is of type " + configListElement.type()
					+ " and is an unknown or not processable type, cannot proceed";
			log.info(msg);
			throw new MutatingAdmissionControllerTypeUnsupportedException(msg);

		}
	}

	private JsonValue getJsonListObject(Config configListElement) throws MutationAdmissionControllerException {
		// objects in a list list may have some specific settings, as it can be a JSON
		// object in it's
		// own right
		// or a set of modifications to an existing JSON object in which case it will
		// have fields for arrayKey, arrayKeyValue and arrayValue where the arrayKey and
		// arrayKeyValue
		// could be used to locate an existing entry in the list
		// in the latter case (which is what we're dealing with here) we need to create
		// a JSON object from the arrayValue and then add
		// fields if they exist
		Config arrayConfigType = configListElement.get(ARRAY_CONFIG_ELEMENT_TYPE_CONFIG);
		Config arrayIndex = configListElement.get(ARRAY_CONFIG_ELEMENT_TYPE_CONFIG);
		Config arrayKey = configListElement.get(ARRAY_KEY_CONFIG);
		Config arrayKeyValue = configListElement.get(ARRAY_KEY_VALUE_CONFIG);
		Config arrayValue = configListElement.get(ARRAY_VALUE_CONFIG);
		// only if all three exist do we "switch" to the sub object approach
		if ((arrayConfigType.exists() && arrayKey.exists() && arrayKeyValue.exists() && arrayValue.exists())
				|| (arrayConfigType.exists() && arrayIndex.exists())) {
			String key = arrayKey.asString().get();
			String keyValue = arrayKeyValue.asString().get();
			JsonValue jsonValue = getJsonValue(arrayValue);
			if (jsonValue.getValueType() != ValueType.OBJECT) {
				throw new MutatingAdmissionControllerTypeMismatchException(
						"Procesing what was expected to be a config object, but the generated Json value has type "
								+ jsonValue.getValueType() + " not an object This is probabaly a programming problem");
			}
			// depending on if it's an index or a key we process some of the array locating
			// info differently
			ConfigArrayElementLocateType locateType;
			try {
				locateType = ConfigArrayElementLocateType.valueOf(arrayConfigType.asString().get().toUpperCase());
			} catch (IllegalArgumentException e) {
				String configKey = configListElement.key().toString();
				String msg = "Config " + configKey + " the value of " + ARRAY_CONFIG_ELEMENT_TYPE_CONFIG
						+ " when mapped to upper case is " + arrayConfigType.asString().get().toUpperCase()
						+ " which not a known action (Available actions are "
						+ Arrays.stream(ConfigArrayElementLocateType.values()).map(enumValue -> enumValue.toString())
								.collect(Collectors.joining(","))
						+ ")";
				log.warning(msg);
				throw new MutatingAdmissionControllerListConfigInvalidLocateTypeException(msg);
			}
			// if this was of type index then add the key to the object and return it,
			// otherwise just hand it back
			switch (locateType) {
			case INDEX:
				// the location info doesn't refer to any value fileds in the JSON, so just
				// return it
				return jsonValue.asJsonObject();
			case KEY:
				// in this case we add the arrayKey / arrayKeyValue form the location info
				return Json.createObjectBuilder(jsonValue.asJsonObject()).add(key, keyValue).build();
			default:
				String msg = "Invalid locate type " + locateType
						+ ", this is a programming problem with the switch statement";
				log.severe(msg);
				throw new MutationAdmissionControllerException(msg);
			}
		} else {
			return getJsonListObject(configListElement);
		}
	}

	private String jsonPrettyPrint(JsonValue val) {
		Map<String, Object> properties = new HashMap<>(1);
		properties.put(JsonGenerator.PRETTY_PRINTING, true);
		StringWriter sw = new StringWriter();
		JsonWriterFactory writerFactory = Json.createWriterFactory(properties);
		JsonWriter jsonWriter = writerFactory.createWriter(sw);
		jsonWriter.write(val);
		jsonWriter.close();
		return (sw.toString());
	}

}
