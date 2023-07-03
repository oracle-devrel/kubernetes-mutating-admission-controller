/*Copyright (c) 2023 Oracle and/or its affiliates.

The Universal Permissive License (UPL), Version 1.0

Subject to the condition set forth below, permission is hereby granted to any
person obtaining a copy of this software, associated documentation and/or data
(collectively the "Software"), free of charge and under any and all copyright
rights in the Software, and any and all patent rights owned or freely
licensable by each licensor hereunder covering either (i) the unmodified
Software as contributed to or provided by such licensor, or (ii) the Larger
Works (as defined below), to deal in both

(a) the Software, and
(b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
one is included with the Software (each a "Larger Work" to which the Software
is contributed by such licensors),

without restriction, including without limitation the rights to copy, create
derivative works of, display, perform, and distribute the Software and make,
use, sell, offer for sale, import, export, have made, and have sold the
Software and the Larger Work(s), and to sublicense the foregoing rights on
either these or other terms.

This license is subject to the following condition:
The above copyright notice and either this complete permission notice or at
a minimum a reference to the UPL must be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */

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

import com.oracle.timg.kubernetes.mutatingadmissioncontroller.exceptions.MutatingAdmissionControllerListArrayIndexInvalidException;
import com.oracle.timg.kubernetes.mutatingadmissioncontroller.exceptions.MutatingAdmissionControllerListArrayIndexOutOfBoundsException;
import com.oracle.timg.kubernetes.mutatingadmissioncontroller.exceptions.MutatingAdmissionControllerListArrayKeyMissingException;
import com.oracle.timg.kubernetes.mutatingadmissioncontroller.exceptions.MutatingAdmissionControllerListArrayKeyValueMissingException;
import com.oracle.timg.kubernetes.mutatingadmissioncontroller.exceptions.MutatingAdmissionControllerListConfigInvalidArrayKeyValueMissingException;
import com.oracle.timg.kubernetes.mutatingadmissioncontroller.exceptions.MutatingAdmissionControllerListConfigInvalidLocateTypeException;
import com.oracle.timg.kubernetes.mutatingadmissioncontroller.exceptions.MutatingAdmissionControllerListConfigMissingFieldsException;
import com.oracle.timg.kubernetes.mutatingadmissioncontroller.exceptions.MutatingAdmissionControllerListConfigMissingLocateTypeException;
import com.oracle.timg.kubernetes.mutatingadmissioncontroller.exceptions.MutatingAdmissionControllerListException;
import com.oracle.timg.kubernetes.mutatingadmissioncontroller.exceptions.MutatingAdmissionControllerListJsonNotAnObjectException;
import com.oracle.timg.kubernetes.mutatingadmissioncontroller.exceptions.MutatingAdmissionControllerSubstitutionMissingPlaceholderException;
import com.oracle.timg.kubernetes.mutatingadmissioncontroller.exceptions.MutatingAdmissionControllerSubstitutionMissingSubstitutionException;
import com.oracle.timg.kubernetes.mutatingadmissioncontroller.exceptions.MutatingAdmissionControllerTypeMismatchException;
import com.oracle.timg.kubernetes.mutatingadmissioncontroller.exceptions.MutatingAdmissionControllerTypeUnsupportedException;
import com.oracle.timg.kubernetes.mutatingadmissioncontroller.exceptions.MutationAdmissionControllerException;

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
import lombok.extern.slf4j.Slf4j;

/**
 * Mutates incoming requests looking for
 */
@Path("/mutate")
@ApplicationScoped
@Counted
@Slf4j
public class MutateLabelToNodeSelector {

	private static final String[] PROTECTED_NAMESPACES = { "kube-system" };
	private static final String[] DO_NOT_SUBSTITUTE_ON_PATHS = {
			"/metadata/annotations/kubectl.kubernetes.io/last-applied-configuration", "/metadata/managedFields" };
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
	public final static String SUBSTITUTION_START_DEFAULT = "...";
	public final static String SUBSTITUTION_END_DEFAULT = "...";

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
	private final String substitutionStart;
	private final String substitutionEnd;
	private final boolean doMappings;
	private final boolean requireMapping;
	private final boolean errorOnMissingMapping;
	private final boolean doSubstitutions;
	private final Map<String, Config> mappingsConfig = new HashMap<>();
	private final Map<String, String> substitutionsConfig = new HashMap<>();
	private final Set<String> doNotSubstituteOnPaths;

	@Inject
	public MutateLabelToNodeSelector(
			@ConfigProperty(name = "mutationEngine.targetNamespaces", defaultValue = "") String targetNamespacesConfig,
			@ConfigProperty(name = "mutationEngine.protectSystemNamespaces", defaultValue = "true") boolean protectSystemNamespaces,
			@ConfigProperty(name = "mutationEngine.input.mappings.doMappings", defaultValue = "true") boolean doMappings,
			@ConfigProperty(name = "mutationEngine.input.mappings.labelName", defaultValue = "targetMapping") String inputLabelName,
			@ConfigProperty(name = "mutationEngine.input.mappings.requireMapping", defaultValue = "false") boolean requireMapping,
			@ConfigProperty(name = "mutationEngine.input.mappings.errorOnMissingMapping", defaultValue = "true") boolean errorOnMissingMapping,
			@ConfigProperty(name = "mutationEngine.input.substitutions.substitutionStart", defaultValue = SUBSTITUTION_START_DEFAULT) String substitutionStart,
			@ConfigProperty(name = "mutationEngine.input.substitutions.substitutionEnd", defaultValue = SUBSTITUTION_END_DEFAULT) String substitutionEnd,
			@ConfigProperty(name = "mutationEngine.input.substitutions.doSubstitutions", defaultValue = "false") boolean doSubstitutions,
			Config config) {
		this.targetNamespaces = Arrays.stream(targetNamespacesConfig.split(",")).map(namespace -> namespace.trim())
				.filter(name -> !name.equals("NONE")).collect(Collectors.toSet());
		log.info("Targeting namespaces before protected namespaces removal" + this.targetNamespaces
				+ " based on input string " + targetNamespacesConfig);
		if (protectSystemNamespaces) {
			for (String protectedNamespace : PROTECTED_NAMESPACES) {
				this.targetNamespaces.remove(protectedNamespace);
			}
			log.info("Targeting namespaces post removal" + this.targetNamespaces + " protected namespaces list is "
					+ PROTECTED_NAMESPACES);
		}
		doNotSubstituteOnPaths = Arrays.stream(DO_NOT_SUBSTITUTE_ON_PATHS).collect(Collectors.toSet());
		log.info("Non substitution paths are " + doNotSubstituteOnPaths);
		this.inputLabelName = inputLabelName;
		log.info("inputLabelName=" + inputLabelName);
		this.requireMapping = requireMapping;
		log.info("requireMapping=" + requireMapping);
		this.errorOnMissingMapping = errorOnMissingMapping;
		log.info("errorOnMissingMapping=" + errorOnMissingMapping);
		this.substitutionStart = substitutionStart;
		log.info("substitutionStart=" + substitutionStart);
		this.substitutionEnd = substitutionEnd;
		log.info("substitutionEnd=" + substitutionEnd);
		this.doSubstitutions = doSubstitutions;
		log.info("doSubstitutions=" + doSubstitutions);
		this.doMappings = doMappings;
		log.info("doMappings=" + doMappings);
		// dumpConfigNames(config, "Root");
		Config mutateConfigSection = config.get("mutationEngine");
		dumpConfigNames(mutateConfigSection, "mutationEngine");
//		move the loadingof mappings and substitutions into a separate method, put 
//		setup a config change watcher to call that to allow for dynamic updates to 
//		the mappings and subsitutions
//		have to put them all in a synchronized losk to prevent things changing while
//		processing a request
		if (doMappings) {
			Config mappingsConfigSection = config.get("mappings");
			if (mappingsConfigSection.exists()) {
				loadMappingsConfig(mappingsConfigSection);
			} else {
				log.warn("Can't load mappings as config section " + mappingsConfigSection.key().toString()
						+ " does not exist");
			}
			// register to be called if updated, this should kick in even if the config
			// section didn't exist this time but does in the future
			mappingsConfigSection.onChange(new MappingConfigUpdateConsumer(this));
		} else {
			log.warn("Mappings disabled in core config, not loading or monitoring for mapping changes");
		}
		log.info("Substitution options doSubstitutions=" + doSubstitutions + ", substitutionStart=" + substitutionStart
				+ ", substitutionEnd=" + substitutionEnd);
		if (doSubstitutions) {
			Config substitutionsConfigSection = config.get("substitutions");
			if (substitutionsConfigSection.exists()) {
				dumpConfigNames(substitutionsConfigSection, "substitutions");
				loadSubstitutionsConfig(substitutionsConfigSection, "");
				log.info("Substitutions map is " + substitutionsConfig.toString());
			} else {
				log.warn("Asked to perform substitutions, but no substitutions section found in the config");
			}
			substitutionsConfigSection.onChange(new SubstitutionConfigUpdateConsumer(this));
		} else {
			log.info("Substitutions disabled in core config, not loading or monitoring for substitution changes");
		}
	}

	void loadMappingsConfig(Config mappingsConfigSection) {
		// stop modi
		synchronized (this) {
			log.info("Mutate -> mappings tree is \n" + dumpConfigTree(mappingsConfigSection, 0, false));
			// remove all the existing mappings
			mappingsConfig.clear();
			mappingsConfigSection.asNodeList().get().stream()
					.forEach(mapping -> mappingsConfig.put(mapping.name(), mapping));
			log.info("Will use the following labels for mappings" + this.mappingsConfig.keySet());

		}
	}

	void loadSubstitutionsConfig(Config substitutionsConfigSection, String namePrefix) {
		if (substitutionsConfigSection.exists()) {
			// synchronize to protect against config changes while processing
			synchronized (this) {
				substitutionsConfig.clear();
				log.info("Substitutsions -> Substitutaions tree is \n"
						+ dumpConfigTree(substitutionsConfigSection, 0, false));
				loadSubstitutions(substitutionsConfigSection, namePrefix);
				log.info("Will use the following labels for substitutaions" + this.substitutionsConfig.keySet());
			}
		} else {
			log.warn("Can't load substitutions as config section " + substitutionsConfigSection.key().toString()
					+ " does not exist");
		}
	}

	private void loadSubstitutions(Config node, String namePrefix) {
		if (node.isLeaf()) {
			substitutionsConfig.put(namePrefix, node.asString().get());
			return;
		}
		// pull the stuff out into a map, discard any non leaf nodes
		node.asNodeList().get().stream().forEach(configNode -> loadSubstitutions(configNode,
				namePrefix.length() == 0 ? configNode.name() : namePrefix + "." + configNode.name()));
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
		log.debug("Empty Response is\n" + jsonb.toJson(response));
		if (requestOperation == null) {
			log.warn("Incomming request " + incommingRequest + " has an unknown operation type "
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
		if (requestObject == null) {
			String msg = "Cannot locate " + REQUEST_OBJECT + "." + OBJECT_OBJECT + " in the request, unable to proceed";
			log.error(msg);
			response.getStatus().setCode(400);
			response.getStatus().setMessage(msg);
			return response;
		}
		// sync on the objet to protect against config changes happening while
		// processing
		synchronized (this) {
			// if we are doing substitutions then build those patched first
			if (doSubstitutions) {
				try {
					log.info("Starting to process substitutions on inbound");
					applySubstitutionsOnInboundJson(requestObject, patchBuilder, "");
					log.info("Completed processing substitutions");
				} catch (MutatingAdmissionControllerSubstitutionMissingPlaceholderException
						| MutatingAdmissionControllerSubstitutionMissingSubstitutionException e) {
					String msg = "Exception processing substitutions on incomming data, " + e.getLocalizedMessage();
					log.warn(msg);
					response.getStatus().setCode(400);
					response.getStatus().setMessage(msg);
					return response;
				}
			}
			if (doMappings) {
				log.info("Starting to process mappings on inbound");
				if (mappingsConfig.size() == 0) {
					log.info("no mappings in the config, just returned request");
					return response;
				}
				String requestKind = requestObject.getString(KIND_FIELD);
				JsonObject metaData = requestObject.getJsonObject(METADATA_OBJECT);
				JsonObject labels = metaData.getJsonObject(LABELS_OBJECT);
				if (labels == null) {
					String msg = "Can't locate " + METADATA_OBJECT + " -> " + LABELS_OBJECT;
					if (requireMapping) {
						msg += ", cannot proceed and rejecting request";
						log.warn(msg);
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
					log.info("Deployment " + requestName + " of type " + requestKind + " has an label for "
							+ inputLabelName + " with value " + inputLabel + " continuing");
				} else {
					String msg = "Deployment " + requestName + " does not contain a label for " + inputLabelName;
					if (requireMapping) {
						msg += ", cannot proceed and rejecting request";
						log.warn(msg);
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
						log.warn(msg);
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
						log.warn("Encountered error processing request\n" + e.getLocalizedMessage());
						response.getStatus().setCode(400);
						response.getStatus().setMessage(e.getLocalizedMessage());
						return response;
					}
				}
			}
			log.info("Completed processing mappings");
		}
		JsonPatch patch = patchBuilder.build();
		log.debug("Resulting patch is " + jsonPrettyPrint(patch.toJsonArray()));
		// try to apply the patch to the json in the request
		JsonObject updatedRequestObject = patch.apply(requestObject);
		log.debug("Updated request after patch is \n" + jsonPrettyPrint(updatedRequestObject));
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
		case MISSING:
		default:
			log.warn("For config node " + selectedMappingConfig.key() + " has unsupported config type of "
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
				log.trace("Json input path " + jsonPathString + " does not exist for config leaf  " + configKey
						+ " will add value to the JSON patch");
				patchBuilder.add(jsonPathString, getJsonValue(selectedMappingConfig));
			} else {
				log.trace("Json input path " + jsonPathString + " exists for config leaf" + configKey
						+ " will add replace to the JSON patch");
				patchBuilder.replace(jsonPathString, getJsonValue(selectedMappingConfig));
			}
		} else {
			if (requestSubValue == null) {
				log.trace("Json input path " + jsonPathString + " config " + configKey
						+ " is an object, no equivalent object in the json input will add");
				patchBuilder.add(jsonPathString, getJsonValue(selectedMappingConfig));
			} else {
				ValueType type = requestSubValue.getValueType();
				if (type != ValueType.OBJECT) {
					String msg = "Json input path " + jsonPathString + " config " + configKey
							+ " is an object, equivalent object in the json input is of type " + type
							+ " and is not a matching type, cannot proceed";
					log.trace(msg);
					throw new MutatingAdmissionControllerTypeMismatchException(msg);
				}
				log.trace("Json input path " + jsonPathString + " config " + configKey
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

	private void applySubstitutionsOnInboundJson(JsonValue requestValue, JsonPatchBuilder patchBuilder, String path)
			throws MutatingAdmissionControllerSubstitutionMissingPlaceholderException,
			MutatingAdmissionControllerSubstitutionMissingSubstitutionException {
		if (doNotSubstituteOnPaths.contains(path)) {
			log.info("Path " + path + " is on the do not substitute list, ignoring");
			return;
		}
		ValueType type = requestValue.getValueType();
		switch (type) {
		case ARRAY: {
			log.debug("JsonValue on path " + path + " is an array");
			JsonArray array = requestValue.asJsonArray();
			for (int i = 0; i < array.size(); i++) {
				JsonValue subValue = array.get(i);
				String subPath = path + "/" + i;
				applySubstitutionsOnInboundJson(subValue, patchBuilder, subPath);
			}
			break;
		}
		case OBJECT: {
			JsonObject object = requestValue.asJsonObject();
			log.debug("JsonValue on path " + path + " is an onbject");
			Set<Map.Entry<String, JsonValue>> entries = object.entrySet();
			for (var entry : entries) {
				applySubstitutionsOnInboundJson(entry.getValue(), patchBuilder, path + "/" + entry.getKey());
			}
			break;
		}
		case STRING: {
			String initialText = requestValue.toString();
			// the above returns a string in quotes, if needed remove first and last "
			initialText = initialText.startsWith("\"") ? initialText.substring(1) : initialText;
			initialText = initialText.endsWith("\"") ? initialText.substring(0, initialText.length() - 1) : initialText;
			log.debug("JsonValue on path " + path
					+ " is a String doing substitutions, inbound text is of type string with value " + initialText);
			String placeholder = locateSubstitutionPlaceholderString(initialText);
			if (placeholder != null) {
				log.debug("Located at least one placeholder in " + initialText + " with name " + placeholder);
				String updatedIncoming = applySubstitutions(initialText);
				JsonValue newJsonValue = convertStringToJsonValue("Inbound JSON " + path, updatedIncoming);
				log.debug("After substitutions replacing path " + path + " with  " + updatedIncoming
						+ " which is of type " + newJsonValue.getValueType());
				patchBuilder.replace(path, newJsonValue);
			}
			break;
		}
		case FALSE:
		case NULL:
		case NUMBER:
		case TRUE:
		default:
			log.debug("JsonValue on path " + path + " is of type " + type
					+ " which cannot define a placeholder for sustitutions");
			break;

		}
	}

	/**
	 * @param textToCheck
	 * @return
	 */
	private String locateSubstitutionPlaceholderString(String textToCheck) {
		// look for the substitution start
		int subStart = textToCheck.indexOf(substitutionStart);
		if (subStart < 0) {
			return null;
		}
		int subEnd = textToCheck.indexOf(substitutionEnd, subStart + substitutionStart.length());
		if (subEnd < 0) {
			return null;
		}
		// return the placeholder text
		return textToCheck.substring(subStart + substitutionStart.length(), subEnd);
	}

	/**
	 * 
	 * 
	 * @param textToProcess
	 * @return
	 * @throws MutatingAdmissionControllerSubstitutionMissingPlaceholderException
	 * @throws MutatingAdmissionControllerSubstitutionMissingSubstitutionException
	 */
	private String applySubstitutions(String textToProcess)
			throws MutatingAdmissionControllerSubstitutionMissingPlaceholderException,
			MutatingAdmissionControllerSubstitutionMissingSubstitutionException {
		String updateText = textToProcess;
		String placeholder = locateSubstitutionPlaceholderString(updateText);
		while (placeholder != null) {
			if (placeholder.length() == 0) {
				throw new MutatingAdmissionControllerSubstitutionMissingPlaceholderException(
						"There is a substitution start and end, but there is no placeholder " + textToProcess);
			}
			if (!substitutionsConfig.containsKey(placeholder)) {
				throw new MutatingAdmissionControllerSubstitutionMissingSubstitutionException(
						" replacing placeholder " + placeholder + " but there is no substitute text available");
			}
			String textToSubstitute = substitutionsConfig.get(placeholder);
			// replace the placeholder and the associated start / end indicators
			updateText = updateText.replace(substitutionStart + placeholder + substitutionEnd, textToSubstitute);
			// done this one, try to locate the next if there is one
			placeholder = locateSubstitutionPlaceholderString(updateText);
		}
		return updateText;
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
			log.debug("Json input path " + jsonPathString + " config input " + configName
					+ " is a list, no equivalent object in the json input, will add");
			patchBuilder.add(jsonPathString, getJsonValue(selectedMappingConfig));
		} else {
			ValueType type = requestSubValue.getValueType();
			if (type != ValueType.ARRAY) {
				String msg = "Json input path " + jsonPathString + " config input " + configKey
						+ " is a list, equivalent object in the json input is of type " + type
						+ " and is not a matching type, cannot proceed";
				log.warn(msg);
				throw new MutatingAdmissionControllerTypeMismatchException(msg);
			}
			JsonArray configJson = requestSubValue.asJsonArray();
			// OK we have a list
			log.debug("Json input path " + jsonPathString + " config " + configKey
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
				log.warn(msg);
				throw new MutatingAdmissionControllerListConfigMissingLocateTypeException(msg);
			}
		} catch (IllegalArgumentException e) {
			String msg = "Config " + configKey + " the value of " + ARRAY_CONFIG_ELEMENT_TYPE_CONFIG
					+ " when mapped to upper case is "
					+ selectedMappingConfig.get(ARRAY_CONFIG_ELEMENT_TYPE_CONFIG).asString().get().toUpperCase()
					+ " which not a known action (Available actions are " + Arrays.stream(MissingMatchAction.values())
							.map(enumValue -> enumValue.toString()).collect(Collectors.joining(","))
					+ ")";
			log.warn(msg);
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
			log.error(msg);
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
			log.warn(msg);
			throw new MutatingAdmissionControllerListConfigMissingFieldsException(msg);

		}
		Integer arrayIndex = arrayIndexValue.asInt().get();
		if (arrayIndex < 0) {
			String msg = "Config " + configKey + " the value of " + ARRAY_INDEX_CONFIG + "cannot be negative";
			log.warn(msg);
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
			log.warn(msg);
			throw new MutatingAdmissionControllerListConfigInvalidArrayKeyValueMissingException(msg);
		}
		log.trace("Json input path prefix " + jsonPathStringPrefix + " config " + configKey
				+ " locating array entry by index of " + arrayIndex);

		if (arrayIndex >= requestArray.size()) {
			String msg = "Json input path prefix " + jsonPathStringPrefix + " config " + configKey
					+ " Specified inted in " + ARRAY_INDEX_CONFIG + " is larger than the JSON array of "
					+ requestArray.size() + ARRAY_INDEX_MISSING_ERROR_CONFIG + " is " + missingMatchAction;
			if (missingMatchAction == MissingMatchAction.ADD) {
				msg += " will create a new item and add to the end of the array";
				log.trace(msg);
				String jsonPathString = jsonPathStringPrefix + "/-";
				patchBuilder.add(jsonPathString, getJsonListValue(selectedMappingConfig));
			} else if (missingMatchAction == MissingMatchAction.ERROR) {
				msg += " erroring";
				log.warn(msg);
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
			log.warn(msg);
			throw new MutatingAdmissionControllerListConfigInvalidArrayKeyValueMissingException(msg);
		}
		// if any do now exist then
		if (!(arrayKey.exists() && arrayKeyValue.exists() && arrayValue.exists())) {
			String msg = "Json input path prefix " + jsonPathStringPrefix + " config " + configKey
					+ " is in an array, yet it does not define at least one of " + ARRAY_KEY_CONFIG + ", "
					+ ARRAY_KEY_VALUE_CONFIG + ", or " + ARRAY_VALUE_CONFIG
					+ " this means that the matching item in the json input array cannot be located or created";
			log.warn(msg);
			throw new MutatingAdmissionControllerListConfigMissingFieldsException(msg);

		}
		// OK, we know this has the fields we want
		String key = arrayKey.asString().get();
		String keyValue = arrayKeyValue.asString().get();
		log.trace("Json input path prefix " + jsonPathStringPrefix + " config " + configKey
				+ " locating array entry by key where " + key + ":" + keyValue);
		Integer arrayIndex = locateJsonArrayIndex(requestArray, key, keyValue, jsonPathStringPrefix,
				arrayKeyMissingError);
		if (arrayIndex == null) {
			String msg = "Json input path prefix " + jsonPathStringPrefix + " config " + configKey
					+ " Could not locate an item in the json input array with fields " + key + ":" + keyValue + " "
					+ ARRAY_KEY_VALUE_NO_MATCH_ACTION_CONFIG + " is " + missingMatchAction;
			if (missingMatchAction == MissingMatchAction.ADD) {
				msg += " will create a new item and add to the end of the array";
				log.trace(msg);
				String jsonPathString = jsonPathStringPrefix + "/-";
				patchBuilder.add(jsonPathString, getJsonListValue(selectedMappingConfig));
			} else if (missingMatchAction == MissingMatchAction.ERROR) {
				msg += " erroring";
				log.warn(msg);
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
			log.debug(
					"Searching json array " + jsonPathString + ", looking for array item with " + key + ":" + keyValue);
			JsonValue potentialIndexedObject = requestArray.get(i);
			ValueType type = potentialIndexedObject.getValueType();
			if (type != ValueType.OBJECT) {
				String msg = "Scanning input array " + jsonPathString + " to locate object with  " + key + ":"
						+ keyValue + " but array item " + i + " is not an object, but instead is of type " + type
						+ " can't handle this";
				log.warn(msg);
				throw new MutatingAdmissionControllerListJsonNotAnObjectException(msg);
			}
			log.trace("Json item at index " + i + " is an object");
			JsonObject indexedObject = potentialIndexedObject.asJsonObject();
			// does this object have the key field ?
			if (!indexedObject.containsKey(key)) {
				String msg = "In json array input " + jsonPathString + "Json item at index " + i
						+ " is an object but does not have a key " + key;
				if (arrayKeyMissingError) {
					msg += ", arrayKeyMissingError is true, throwing an error";
					log.warn(msg);
					throw new MutatingAdmissionControllerListArrayKeyMissingException(msg);
				} else {
					log.trace("In json array input " + jsonPathString + "Json item at index " + i
							+ " is an object but does not have a key " + key + " ignoring this item");
					continue;
				}
			}
			// it has the key field, is the key a string
			JsonValue potentialKeyValue = indexedObject.get(key);
			ValueType potentialKeyValueType = potentialKeyValue.getValueType();
			if (potentialKeyValueType != ValueType.STRING) {
				log.trace("In json array input " + jsonPathString + "Json item at index " + i
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
				log.trace("In json array input " + jsonPathString + "Json item at index " + i
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
			log.debug("Json input path prefix " + jsonPathString + " config " + configKey
					+ " is a leaf object, no equivalent object in the json input  will add");
			patchBuilder.add(jsonPathString, getJsonValue(selectedMappingConfig));
		} else {
			ValueType type = requestValue.getValueType();
			if ((type == ValueType.STRING) || (type == ValueType.NUMBER) || (type == ValueType.NULL)) {
				log.debug("Json input path prefix " + jsonPathString + " config " + configKey
						+ " is a leaf, equivalent object in the jdon input  is of type " + type + " will replace");
				patchBuilder.replace(jsonPathString, getJsonValue(selectedMappingConfig));
			} else {
				String msg = "Json input path prefix " + jsonPathString + " config " + configKey
						+ " is a leaf, equivalent object in the json input is of type " + type
						+ " and is not a matching type, cannot proceed";
				log.warn(msg);
				throw new MutatingAdmissionControllerTypeMismatchException(msg);
			}
		}
		return;
	}

	private JsonValue getJsonValue(Config selectedMappingConfig) throws MutationAdmissionControllerException {
		String configNodeName = selectedMappingConfig.key().toString();
		log.debug("Processing config node" + configNodeName);
		JsonValue builtObject = null;
		if (selectedMappingConfig.type() == Type.OBJECT) {
			log.trace("Config value for " + configNodeName + " is an object");
			JsonObjectBuilder builder = Json.createObjectBuilder();
			List<Config> nodes = selectedMappingConfig.asNodeList().get();
			for (Config node : nodes) {
				builder.add(node.name(), getJsonValue(node));
			}
			builtObject = builder.build();
		} else if (selectedMappingConfig.type() == Type.LIST) {
			log.trace("Config value for " + configNodeName + " is a list");
			JsonArrayBuilder builder = Json.createArrayBuilder();
			List<Config> nodes = selectedMappingConfig.asNodeList().get();
			for (Config node : nodes) {
				builder.add(getJsonListValue(node));
			}
			builtObject = builder.build();
		} else if (selectedMappingConfig.type() == Type.VALUE) {
			String selectedMappingValueString = selectedMappingConfig.asString().orElse(null);
			builtObject = convertStringToJsonValue("Config " + configNodeName, selectedMappingValueString);
		} else {
			String msg = "Config object " + configNodeName + " is type " + selectedMappingConfig.type()
					+ " which is an unsupported input type ";
			log.warn(msg);
			return Json.createValue(msg);
		}
		log.debug("Returning " + builtObject);
		return builtObject;
	}

	/**
	 * @param sourceName
	 * @param builtObject
	 * @param valueString
	 * @return
	 * @throws MutatingAdmissionControllerSubstitutionMissingPlaceholderException
	 * @throws MutatingAdmissionControllerSubstitutionMissingSubstitutionException
	 */
	private JsonValue convertStringToJsonValue(String sourceName, String valueString)
			throws MutatingAdmissionControllerSubstitutionMissingPlaceholderException,
			MutatingAdmissionControllerSubstitutionMissingSubstitutionException {
		if (valueString == null) {
			log.trace("Source location " + sourceName + " is null, returning null");
			return JsonValue.NULL;
		}
		log.trace("Source location " + sourceName + " has value " + valueString);
		// the config tree doesn't have any accessible info as to the actual type of
		// data it holds
		// we can get this as a string though in most cases, so let's try processing
		// that
		// we will do the following precidence
		// integer, long, double then default to string which will cover strings as well
		// as booleans in string form
		try {
			int v = Integer.parseInt(valueString);
			log.trace("Source location " + sourceName + ", Treating value " + valueString + " as an int");
			// it parsed
			return Json.createValue(v);
		} catch (NumberFormatException nfe) {
			// it's not an int, that's fine
		}
		try {
			long v = Long.parseLong(valueString);
			// it parsed
			log.trace("Source location " + sourceName + ", treating value " + valueString + " as a long");
			return Json.createValue(v);
		} catch (NumberFormatException nfe) {
			// it's not a long, that's fine
		}
		try {
			double v = Double.parseDouble(valueString);
			// it parsed
			log.trace("Source location " + sourceName + ", treating value " + valueString + " as a double");
			return Json.createValue(v);
		} catch (NumberFormatException nfe) {
			// it's not a double, that's fine
		}
		// if it's true / false use that, note that this uses strict true / false (but
		// is case insensitive) though yes / no are allowed in YAML
		// (and thus in the Helidon config system) they are not allowed here.
		if ((valueString.equalsIgnoreCase(Boolean.TRUE.toString())
				|| valueString.equalsIgnoreCase(Boolean.FALSE.toString()))) {
			boolean v = Boolean.parseBoolean(valueString);
			log.trace("Source location " + sourceName + ", treating value " + valueString + " as boolean");
			return v ? JsonValue.TRUE : JsonValue.FALSE;
		}
		// it's a general string option, are we doing substitutsions on the inputs ?
		if (doSubstitutions) {
			// is this is a leaf of type STRING in the incoming JSON and it contains a
			// substitution start and end then create a modification in JSON patch, this may
			// get overridden shortly with the rest of the code
			String placeholder = locateSubstitutionPlaceholderString(valueString);
			if (placeholder != null) {
				log.debug("Located at least one placeholder in " + valueString + " with name " + placeholder);
				String substitutedValueString = applySubstitutions(valueString);
				JsonValue builtObject = convertStringToJsonValue("Post substitutions " + sourceName,
						substitutedValueString);
				// now we've done any substitutions see what the new value type has become
				log.trace("Source location " + sourceName + "Origional type was probabaly processed type is "
						+ builtObject.getValueType());
				return builtObject;
			}
		}
		return Json.createValue(valueString);
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
			log.warn(msg);
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
				log.warn(msg);
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
				log.error(msg);
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
