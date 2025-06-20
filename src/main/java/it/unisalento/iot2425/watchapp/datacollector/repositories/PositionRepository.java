package it.unisalento.iot2425.watchapp.datacollector.repositories;

import it.unisalento.iot2425.watchapp.datacollector.domain.Data;
import it.unisalento.iot2425.watchapp.datacollector.domain.Position;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Arrays;
import java.util.List;

public interface PositionRepository extends MongoRepository<Position, String> {


    List<Position> findByDateAndPatientId(String date, String patientId);

}
