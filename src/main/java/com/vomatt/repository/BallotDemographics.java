package com.vomatt.repository;

import java.time.LocalDate;
import java.util.UUID;

import com.vomatt.entity.Gender;

/** One Ballot reduced to what a Poll report may use: its option (null once sealed) and the voter's demographics. */
public record BallotDemographics(UUID optionId, LocalDate birthday, Gender gender) {
}
