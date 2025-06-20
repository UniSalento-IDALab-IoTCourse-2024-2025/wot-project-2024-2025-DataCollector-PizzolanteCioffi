package it.unisalento.iot2425.watchapp.datacollector.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(code= HttpStatus.NOT_FOUND)
public class DataNotFoundException extends Exception{
}
