package com.example.bank.media;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
public class RoomHangupController {
    private final RoomHangupService service;
    public RoomHangupController(RoomHangupService service){this.service=service;}
    @PostMapping("/internal/room/{roomId}/hangup")
    public void hangup(@PathVariable String roomId,@RequestBody Map<String,Object> input){service.hangup(roomId,input);}
}
