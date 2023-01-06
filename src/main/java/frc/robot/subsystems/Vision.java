package frc.robot.subsystems;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Optional;

import org.photonvision.PhotonCamera;
import org.photonvision.targeting.PhotonPipelineResult;
import org.photonvision.targeting.PhotonTrackedTarget;

import edu.wpi.first.apriltag.AprilTag;
import edu.wpi.first.apriltag.AprilTagFieldLayout;
import edu.wpi.first.apriltag.AprilTagFields;
import edu.wpi.first.apriltag.AprilTagFieldLayout.OriginPosition;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.geometry.Transform3d;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.DriverStation.Alliance;
import edu.wpi.first.wpilibj.smartdashboard.SendableChooser;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import frc.robot.Constants;
import frc.robot.Constants.CameraConstants.CameraDefaults;

public class Vision extends SubsystemBase {
    private enum AprilTagOption {
        ;
        
        private int id;
        private AprilTag

        private AprilTagOption(int id) {

        }
    }

    private PhotonCamera driver_cam;

    private PhotonCamera apriltag_cam;
    private Transform3d centerToAprilTagCamera;
    private PhotonPipelineResult current_captures = new PhotonPipelineResult();

    private AprilTagFieldLayout tag_locations;

    private SendableChooser<>

    public Vision() {
        this.driver_cam = new PhotonCamera("drivervision");
        driver_cam.setDriverMode(true);

        this.apriltag_cam = new PhotonCamera("apriltagvision");
        apriltag_cam.setDriverMode(false);
        this.centerToAprilTagCamera = CameraDefaults.MountOne.getTransformation();

        // assume that we are testing within our own facilities while testing, else use the current field resource file.
        if (Constants.testing) {
            tag_locations = new AprilTagFieldLayout(new ArrayList<AprilTag>(), 0, 0);
        } else {
            try {
                tag_locations = new AprilTagFieldLayout(AprilTagFields.k2022RapidReact.m_resourceFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (DriverStation.getAlliance() == Alliance.Blue) {
            tag_locations.setOrigin(OriginPosition.kBlueAllianceWallRightSide);
        } else {
            tag_locations.setOrigin(OriginPosition.kRedAllianceWallRightSide);
        }

    }

    @Override
    public void periodic() {
        if (apriltag_cam.getLatestResult().hasTargets()) {
            current_captures = apriltag_cam.getLatestResult();
        } else {
            current_captures = new PhotonPipelineResult();
        }
    }
    
    // uses optionals for optimal handling outside of the method.
    public Optional<Pose2d> getRobotPoseContributor() {
        if (current_captures.hasTargets()) {
            PhotonTrackedTarget best_target = current_captures.getBestTarget();
            if (best_target.getPoseAmbiguity() < .1) {
                // good capture
                Pose3d fieldRelativeAprilTagPose = tag_locations.getTagPose(best_target.getFiducialId()).get();
                Pose2d calculatedRobotPose = 
                    fieldRelativeAprilTagPose
                        .transformBy(best_target.getBestCameraToTarget().inverse())
                        .transformBy(centerToAprilTagCamera.inverse())
                        .toPose2d();
                return Optional.of(calculatedRobotPose);
            }
        }
        return Optional.empty();
    }
}