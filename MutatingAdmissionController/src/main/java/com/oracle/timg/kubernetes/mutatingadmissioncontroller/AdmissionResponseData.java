package com.oracle.timg.kubernetes.mutatingadmissioncontroller;

import java.util.Base64;

import jakarta.json.JsonArray;
import jakarta.json.JsonPatch;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.experimental.SuperBuilder;
import lombok.extern.java.Log;

@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Data
@Log
public class AdmissionResponseData {
	@NonNull
	private String uid;
	@NonNull
	private Boolean allowed;
	@NonNull
	@Builder.Default
	private String patchType = "JSONPatch";
	private String patch;

	public AdmissionResponseData addPatches(JsonPatch patchDetails) {
		JsonArray jsonPatches = patchDetails.toJsonArray();
//		JsonArray jsonPatches = Json.createArrayBuilder(patches).build();
		String jsonString = jsonPatches.toString();
		log.info("Adding patches\n" + jsonString);
		this.patch = Base64.getEncoder().encodeToString(jsonString.getBytes());
		return this;
	}
}
