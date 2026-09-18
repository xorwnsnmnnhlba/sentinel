package com.sentinel.application;

public class NotFoundException extends RuntimeException {

	public NotFoundException(String resource, long id) {
		super(resource + " " + id + " not found");
	}

}
