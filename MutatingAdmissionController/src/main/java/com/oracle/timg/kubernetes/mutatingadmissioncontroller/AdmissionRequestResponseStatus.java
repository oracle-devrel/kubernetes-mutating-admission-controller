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
public class AdmissionRequestResponseStatus {
	@Builder.Default
	private int code = 200;
	@Builder.Default
	private String message = "All A-OK";

}
