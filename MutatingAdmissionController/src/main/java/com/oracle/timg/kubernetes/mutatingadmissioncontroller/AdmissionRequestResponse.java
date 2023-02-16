package com.oracle.timg.kubernetes.mutatingadmissioncontroller;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Data
public class AdmissionRequestResponse {
	@Builder.Default
	private String apiVersion = "admission.k8s.io/v1";
	@Builder.Default
	private String kind = "AdmissionReview";
	private AdmissionResponseData response;
	@Builder.Default
	private AdmissionRequestResponseStatus status = new AdmissionRequestResponseStatus();

}
