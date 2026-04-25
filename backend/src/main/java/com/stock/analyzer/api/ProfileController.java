package com.stock.analyzer.api;

import com.stock.analyzer.infra.ProfileRepository;
import com.stock.analyzer.model.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/profiles")
public class ProfileController {
    private final ProfileRepository profileRepository;

    public ProfileController(ProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    @GetMapping
    public List<Profile> getAll() {
        return profileRepository.findAll();
    }

    @PostMapping
    public Profile save(@RequestBody Profile profile) {
        return profileRepository.save(profile);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        profileRepository.deleteById(id);
    }
}
