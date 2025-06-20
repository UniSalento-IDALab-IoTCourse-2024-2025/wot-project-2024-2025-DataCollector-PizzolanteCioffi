package it.unisalento.iot2425.watchapp.datacollector.restcontrollers;

import it.unisalento.iot2425.watchapp.datacollector.domain.Data;
import it.unisalento.iot2425.watchapp.datacollector.domain.Position;
import it.unisalento.iot2425.watchapp.datacollector.dto.AllDataDTO;
import it.unisalento.iot2425.watchapp.datacollector.dto.DataDTO;
import it.unisalento.iot2425.watchapp.datacollector.dto.DataListDTO;
import it.unisalento.iot2425.watchapp.datacollector.dto.PositionDTO;
import it.unisalento.iot2425.watchapp.datacollector.exceptions.DataNotFoundException;
import it.unisalento.iot2425.watchapp.datacollector.repositories.DataRepository;
import it.unisalento.iot2425.watchapp.datacollector.repositories.PositionRepository;
import it.unisalento.iot2425.watchapp.datacollector.security.JwtUtilities;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/data")
public class DataRestController {

    @Autowired
    private JwtUtilities jwtUtilities;

    @Autowired
    private DataRepository dataRepository;

    @Autowired
    private PositionRepository positionRepository;


    @PreAuthorize("hasRole('ROLE_Patient')")
    @RequestMapping(value = "/saveDataFromFB",
            method = RequestMethod.POST,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> saveDataFromFB(HttpServletRequest request) {

        final String authHeader = request.getHeader("Authorization");

        String token = authHeader.substring(7);

        String patientId=jwtUtilities.extractUserId(token);


        String uri = "http://54.167.160.164:8080/api/users/patient/" + patientId;

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        HttpEntity<String> entity = new HttpEntity<>("parameters", headers);
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(uri, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
        Map<String, Object> httpData = response.getBody();
        String accessToken = (String) httpData.get("accessToken");


        // Formato richiesto: "HH:mm"
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");

        // Ora attuale
        ZonedDateTime now=ZonedDateTime.now(ZoneId.of("Europe/Rome"));
        String endTime = now.toLocalTime().format(timeFormatter);


        uri = "https://api.fitbit.com/1/user/-/activities/heart/date/today/today/1min/time/00:00/" + endTime + ".json";

        restTemplate = new RestTemplate();
        headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        entity = new HttpEntity<>("parameters", headers);
        response = restTemplate.exchange(uri, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
        httpData = response.getBody();

        Map<String, Object> intraday= (Map<String, Object>) httpData.get("activities-heart-intraday");
        List <Map<String, Object>> dataset = (List<Map<String, Object>>) intraday.get("dataset");

        // Mappa: ora → lista di valori
        Map<Integer, List<Double>> hourToValues = new HashMap<>();

        for (Map<String, Object> entry : dataset) {
            String timeStr = (String) entry.get("time");
            int hour = Integer.parseInt(timeStr.split(":")[0]);
            double value = ((Number) entry.get("value")).doubleValue();

            hourToValues.computeIfAbsent(hour, k -> new ArrayList<>()).add(value);
        }


        Map<Integer, Double> hourlyAverages = new TreeMap<>();
        for (Map.Entry<Integer, List<Double>> e : hourToValues.entrySet()) {
            List<Double> values = e.getValue();
            double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            hourlyAverages.put(e.getKey(), avg);
        }

        String date= LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));


        uri="https://api.fitbit.com/1.2/user/-/sleep/date/" + date + ".json";
        restTemplate = new RestTemplate();
        headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        entity = new HttpEntity<>("parameters", headers);
        response = restTemplate.exchange(uri, HttpMethod.GET, entity, new ParameterizedTypeReference<Map<String, Object>>() {});
        httpData = response.getBody();

        Map<String, Object> summary = (Map<String, Object>) httpData.get("summary");
        int totalMinutesAsleep = (int) summary.get("totalMinutesAsleep");


        for (Map.Entry<Integer, Double> entry : hourlyAverages.entrySet()) {
            Integer hour = entry.getKey();
            Integer average = entry.getValue().intValue();

            String timeStr = String.format("%02d:00", hour);

            boolean exists = dataRepository.existsByPatientIdAndDateAndTime(patientId, date, timeStr);
            if(exists==false){
                Data data = new Data();
                data.setPatientId(patientId);
                data.setDate(date);
                data.setTime(timeStr);
                data.setHeartRate(average);
                data.setSleepDuration(String.valueOf(totalMinutesAsleep));

                dataRepository.save(data);

            } else {
                Data data = dataRepository.findByPatientIdAndDateAndTime(patientId, date, timeStr);
                data.setHeartRate(average);
                data.setSleepDuration(String.valueOf(totalMinutesAsleep));

                dataRepository.save(data);
            }
        }


        return ResponseEntity.status(HttpStatus.OK).body("Dati salvati correttamente");
    }


    @PreAuthorize("hasRole('Patient')")
    @RequestMapping(value = "/saveCallDataFromFE",
            method = RequestMethod.POST,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> saveCallDataFromFE(@RequestBody List<Map<String, Object>> calls,
                                                    HttpServletRequest request) {

        final String authHeader = request.getHeader("Authorization");
        String token = authHeader.substring(7);
        String patientId = jwtUtilities.extractUserId(token);

        // Mappa: data → ora → lista durate
        Map<String, Map<String, List<Long>>> groupedDurations = new HashMap<>();

        for (Map<String, Object> call : calls) {
            long timestamp = ((Number) call.get("timestamp")).longValue();
            int duration = ((Number) call.get("duration")).intValue();


            Instant instant = Instant.ofEpochMilli(timestamp);
            ZonedDateTime dateTime = instant.atZone(ZoneId.of("Europe/Rome"));

            String dateStr = dateTime.toLocalDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String hourStr = String.format("%02d:00", dateTime.getHour());


            groupedDurations
                    .computeIfAbsent(dateStr, d -> new HashMap<>())
                    .computeIfAbsent(hourStr, h -> new ArrayList<>())
                    .add((long) duration);
        }

        for (Map.Entry<String, Map<String, List<Long>>> dateEntry : groupedDurations.entrySet()) {
            String date = dateEntry.getKey();
            Map<String, List<Long>> hourToDurations = dateEntry.getValue();

            for (Map.Entry<String, List<Long>> hourEntry : hourToDurations.entrySet()) {
                String timeStr = hourEntry.getKey();
                long totalDuration = hourEntry.getValue().stream().mapToLong(Long::longValue).sum();

                boolean exists = dataRepository.existsByPatientIdAndDateAndTime(patientId, date, timeStr);
                if (!exists) {

                    // Recupera l'ultimo record per prendere sleepDuration
                    Data lastRecord = dataRepository.findTopByPatientIdOrderByDateDescTimeDesc(patientId);
                    String sleepDuration = (lastRecord != null) ? lastRecord.getSleepDuration() : "0";  // default 0 se nulla

                    Data data = new Data();
                    data.setPatientId(patientId);
                    data.setDate(date);
                    data.setSleepDuration(sleepDuration);
                    data.setTime(timeStr);

                    data.setCallDuration((int) totalDuration);
                    dataRepository.save(data);
                } else {
                    Data data = dataRepository.findByPatientIdAndDateAndTime(patientId, date, timeStr);
                    data.setCallDuration((int) totalDuration);
                    dataRepository.save(data);
                }
            }
        }

        return ResponseEntity.ok("Dati chiamate salvati correttamente.");
    }

    @PreAuthorize("hasRole('Patient')")
    @RequestMapping(value= "/position",
            method = RequestMethod.POST,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> savePosition (HttpServletRequest request, @RequestBody Map <String,String> positionData ){

        final String authHeader = request.getHeader("Authorization");
        String token = authHeader.substring(7);
        String patientId = jwtUtilities.extractUserId(token);

        String latitude=positionData.get("latitude");
        String longitude=positionData.get("longitude");

        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Europe/Rome"));
        String time = now.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"));

        Position position=new Position();
        position.setDate(date);
        position.setLatitude(latitude);
        position.setLongitude(longitude);
        position.setPatientId(patientId);
        position.setTime(time);

        positionRepository.save(position);


        PositionDTO positionDTO=new PositionDTO();
        positionDTO.setDate(date);
        positionDTO.setLatitude(latitude);
        positionDTO.setLongitude(longitude);
        positionDTO.setPatientId(patientId);
        positionDTO.setTime(time);
        positionDTO.setId(position.getId());

        return ResponseEntity.ok(positionDTO);



    }


    @RequestMapping(value = "/",
            method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AllDataDTO getAll (HttpServletRequest request) {
        AllDataDTO allData = new AllDataDTO();
        List<PositionDTO> positionList = positionRepository.findAll().stream()
                .map(position -> {
                    PositionDTO dto = new PositionDTO();
                    dto.setId(position.getId());
                    dto.setId(position.getId());
                    dto.setDate(position.getDate());
                    dto.setTime(position.getTime());
                    dto.setPatientId(position.getPatientId());
                    dto.setLatitude(position.getLatitude());
                    dto.setLongitude(position.getLongitude());
                    return dto;
                }).collect(Collectors.toList());

        List<DataDTO> dataList = dataRepository.findAll().stream()
                .map(data -> {
                    DataDTO dto = new DataDTO();
                    dto.setId(data.getId());
                    dto.setDate(data.getDate());
                    dto.setTime(data.getTime());
                    dto.setCallDuration(data.getCallDuration());
                    dto.setPatientId(data.getPatientId());
                    dto.setSleepDuration(data.getSleepDuration());
                    dto.setHeartRate(data.getHeartRate());
                    dto.setPatientId(data.getPatientId());
                    return dto;
                }).collect(Collectors.toList());

        allData.setData(dataList);
        allData.setPositions(positionList);

        return allData;
    }

    @RequestMapping(value = "/getAllByDate",
            method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AllDataDTO getAllByDate (HttpServletRequest request, @RequestParam String date, @RequestParam String patientId) {
        AllDataDTO allData = new AllDataDTO();
        List<PositionDTO> positionList = positionRepository.findByDateAndPatientId(date, patientId).stream()
                .map(position -> {
                    PositionDTO dto = new PositionDTO();
                    dto.setId(position.getId());
                    dto.setId(position.getId());
                    dto.setDate(position.getDate());
                    dto.setTime(position.getTime());
                    dto.setPatientId(position.getPatientId());
                    dto.setLatitude(position.getLatitude());
                    dto.setLongitude(position.getLongitude());
                    return dto;
                }).collect(Collectors.toList());

        List<DataDTO> dataList = dataRepository.findByDateAndPatientId(date, patientId).stream()
                .map(data -> {
                    DataDTO dto = new DataDTO();
                    dto.setId(data.getId());
                    dto.setDate(data.getDate());
                    dto.setTime(data.getTime());
                    dto.setCallDuration(data.getCallDuration());
                    dto.setPatientId(data.getPatientId());
                    dto.setSleepDuration(data.getSleepDuration());
                    dto.setHeartRate(data.getHeartRate());
                    dto.setPatientId(data.getPatientId());
                    return dto;
                }).collect(Collectors.toList());

        allData.setData(dataList);
        allData.setPositions(positionList);

        return allData;
    }




    @RequestMapping(value = "/deletePosition/{id}", method = RequestMethod.DELETE)
    public void deletePosition(@PathVariable String id) throws DataNotFoundException {

        try{
            positionRepository.deleteById(id);
        } catch (Exception e) {
            throw new DataNotFoundException();
        }

    }

    @RequestMapping(value = "/deleteData/{id}", method = RequestMethod.DELETE)
    public void deleteData(@PathVariable String id) throws DataNotFoundException {

        try{
            dataRepository.deleteById(id);
        } catch (Exception e) {
            throw new DataNotFoundException();
        }

    }









}
