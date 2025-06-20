package it.unisalento.iot2425.watchapp.datacollector.repositories;

import it.unisalento.iot2425.watchapp.datacollector.domain.Data;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Arrays;
import java.util.List;

public interface DataRepository extends MongoRepository<Data, String> {
    boolean existsByPatientIdAndDateAndTime(String patientId, String date, String hour);

    Data findByPatientIdAndDateAndTime(String patientId, String date, String hour);

    Data findByPatientIdAndTime(String patientId, String timeStr);

    boolean existsByPatientIdAndTime(String patientId, String timeStr);

    List<Data> findByDateAndPatientId(String date, String patientId);

    Data findTopByPatientIdOrderByDateDescTimeDesc(String patientId);

}
