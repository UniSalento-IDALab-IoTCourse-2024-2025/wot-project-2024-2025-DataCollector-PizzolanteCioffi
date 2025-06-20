package it.unisalento.iot2425.watchapp.datacollector.dto;

import java.util.List;

public class AllDataDTO {

    private List<DataDTO> data;
    private List<PositionDTO> positions;

    public List<DataDTO> getData() {
        return data;
    }

    public void setData(List<DataDTO> data) {
        this.data = data;
    }

    public List<PositionDTO> getPositions() {
        return positions;
    }

    public void setPositions(List<PositionDTO> positions) {
        this.positions = positions;
    }
}
