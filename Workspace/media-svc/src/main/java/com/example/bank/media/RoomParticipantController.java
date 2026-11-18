package com.example.bank.media;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
public class RoomParticipantController{
    private final RoomParticipantService service;
    public RoomParticipantController(RoomParticipantService service){this.service=service;}
    @PostMapping("/internal/room/{roomId}/participants") public void update(@PathVariable String roomId,@RequestBody Map<String,Object> context){service.update(roomId,context);}
}
