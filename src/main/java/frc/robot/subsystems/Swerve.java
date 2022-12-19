package frc.robot.subsystems;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import com.ctre.phoenix.motorcontrol.NeutralMode;
import com.ctre.phoenix.sensors.Pigeon2;
import com.pathplanner.lib.PathPlanner;
import com.pathplanner.lib.PathPlannerTrajectory;
import com.pathplanner.lib.auto.PIDConstants;
import com.pathplanner.lib.auto.SwerveAutoBuilder;

import frc.robot.Robot;
import frc.swervelib.util.SwerveSettings;
import frc.swervelib.util.SwerveSettings.PATH_LIST;
import frc.swervelib.util.SwerveSettings.ShuffleboardConstants.BOARD_PLACEMENT;
import frc.swervelib.util.SwerveModule;
import edu.wpi.first.wpilibj2.command.SequentialCommandGroup;
import edu.wpi.first.wpilibj2.command.InstantCommand;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.math.kinematics.SwerveDriveKinematics;
import edu.wpi.first.math.kinematics.SwerveDriveOdometry;
import edu.wpi.first.math.kinematics.SwerveModulePosition;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.wpilibj.shuffleboard.BuiltInLayouts;
import edu.wpi.first.wpilibj.shuffleboard.BuiltInWidgets;
import edu.wpi.first.wpilibj.shuffleboard.Shuffleboard;
import edu.wpi.first.wpilibj.shuffleboard.ShuffleboardLayout;
import edu.wpi.first.wpilibj.shuffleboard.ShuffleboardTab;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.SubsystemBase;

public class Swerve extends SubsystemBase {
    public HashMap<String, Command> events = new HashMap<String, Command>();
    public HashMap<PATH_LIST, PathPlannerTrajectory> trajectories = new HashMap<PATH_LIST, PathPlannerTrajectory>();
    public SwerveDriveOdometry swerveOdometry;
    public SwerveModule[] mSwerveMods;
    public Pigeon2 gyro;
    public ShuffleboardTab sub_tab;
    public SwerveAutoBuilder builder;
    public double chassis_speed; // meters / second
    public Pose2d last_pose;
    public Instant last_instant;


    public Swerve() {
        this.gyro = new Pigeon2(SwerveSettings.Swerve.pigeonID);
        gyro.configFactoryDefault();
        zeroGyro();
        this.chassis_speed = 0;
        this.sub_tab = Shuffleboard.getTab("swerve_tab");

        mSwerveMods = new SwerveModule[] {
            new SwerveModule(0, SwerveSettings.Swerve.Mod0.constants, sub_tab),
            new SwerveModule(1, SwerveSettings.Swerve.Mod1.constants, sub_tab),
            new SwerveModule(2, SwerveSettings.Swerve.Mod2.constants, sub_tab),
            new SwerveModule(3, SwerveSettings.Swerve.Mod3.constants, sub_tab)
        };

        this.swerveOdometry = new SwerveDriveOdometry(SwerveSettings.Swerve.swerveKinematics, getYaw(), getModulePositions());
        last_pose = swerveOdometry.getPoseMeters();
        last_instant = Instant.now();

        this.builder = new SwerveAutoBuilder(
            this::getPose, // Pose2d supplier
            this::resetOdometry, // Pose2d consumer, used to reset odometry at the beginning of auto
            SwerveSettings.Swerve.swerveKinematics, // SwerveDriveKinematics
            new PIDConstants(5.0, 0.0, 0.0), // PID constants to correct for translation error (used to create the X and Y PID controllers)
            new PIDConstants(0.5, 0.0, 0.0), // PID constants to correct for rotation error (used to create the rotation controller)
            this::setModuleStates, // Module states consumer used to output to the drive subsystem
            events,
            this // The drive subsystem. Used to properly set the requirements of path following commands
        );

        sub_tab.addDouble("Pigeon IMU", () -> getYaw().getDegrees())
        .withSize(2, 2)
        .withPosition(4, 3)
        .withWidget(BuiltInWidgets.kGyro);

        // sub_tab.getLayout("IMU", BuiltInLayouts.kGrid)
        // .withProperties(Map.of("Number of columns", 1, "Number of rows", 1, "Label position", "HIDDEN"))
        // .withSize(2, 2)
        // .withPosition(1, 3)
        // .addDouble("IMU", () -> getYaw().getDegrees())
        // .withWidget(BuiltInWidgets.kGyro);

        sub_tab.addDouble("Chassis Speedometer: MPS", () -> getChassisSpeed())
        .withWidget(BuiltInWidgets.kDial)
        .withProperties(Map.of("Min", 0, "Max", SwerveSettings.Swerve.maxSpeed, "Show value", true))
        .withSize(4, 3)
        .withPosition(3, 0);


        for (int i = 0; i < mSwerveMods.length; i++) {
            SwerveModule cur = mSwerveMods[i];
            BOARD_PLACEMENT placement = BOARD_PLACEMENT.valueOf("RPM" + i);
            ShuffleboardLayout layout = sub_tab.getLayout("mod " + cur.moduleNumber, BuiltInLayouts.kGrid)
            .withProperties(Map.of("Label Position", "HIDDEN", "Number of columns", 1, "Number of rows", 2))
            .withPosition(placement.getX(), placement.getY())
            .withSize(2, 2);

            layout.addDouble(1 + " " + i + 1, () -> Math.abs(cur.mDriveMotor.getObjectRotationsPerMinute()))
            .withWidget(BuiltInWidgets.kDial)
            .withProperties(Map.of("Min", 0, "Max", 800, "Show value", true));

            layout.addDouble(1 + " " + i, () -> cur.mDriveMotor.getSupplyCurrent())
            .withWidget(BuiltInWidgets.kNumberBar)
            .withProperties(Map.of("Min", 0, "Max", SwerveSettings.Swerve.drivePeakCurrentLimit));
        }

        // iterates through the available path enums, and then puts them into the available path
        // hashmap. use the enums themselves to choose which path.
        for (PATH_LIST enu: PATH_LIST.values()) {
            trajectories.put(enu, PathPlanner.loadPath(enu.toString(), enu.getConstraints()));
        }
    }

    public void drive(Translation2d translation, double rotation, boolean fieldRelative, boolean isOpenLoop) {
        SwerveModuleState[] swerveModuleStates =
            SwerveSettings.Swerve.swerveKinematics.toSwerveModuleStates(
                fieldRelative ? ChassisSpeeds.fromFieldRelativeSpeeds(
                                    translation.getX(), 
                                    translation.getY(), 
                                    rotation, 
                                    getYaw()
                                )
                                : new ChassisSpeeds(
                                    translation.getX(), 
                                    translation.getY(), 
                                    rotation)
                                );
        SwerveDriveKinematics.desaturateWheelSpeeds(swerveModuleStates, SwerveSettings.Swerve.maxSpeed);

        for(SwerveModule mod : mSwerveMods){
            mod.setDesiredState(swerveModuleStates[mod.moduleNumber], isOpenLoop);
        }
    }    

    /* Used by SwerveControllerCommand in Auto */
    public void setModuleStates(SwerveModuleState[] desiredStates) {
        SwerveDriveKinematics.desaturateWheelSpeeds(desiredStates, SwerveSettings.Swerve.maxSpeed);
        
        for(SwerveModule mod : mSwerveMods){
            mod.setDesiredState(desiredStates[mod.moduleNumber], false);
        }
    }    

    public Pose2d getPose() {
        return swerveOdometry.getPoseMeters();
    }

    public void resetOdometry(Pose2d pose) {
        swerveOdometry.resetPosition(getYaw(), getModulePositions(), pose);
    }

    public SwerveModuleState[] getStates(){
        SwerveModuleState[] states = new SwerveModuleState[4];
        for(SwerveModule mod : mSwerveMods){
            states[mod.moduleNumber] = mod.getState();
        }
        return states;
    }

    public SwerveModulePosition[] getModulePositions() {
        SwerveModulePosition[] states = new SwerveModulePosition[4];
        for(SwerveModule mod : mSwerveMods) {
            states[mod.moduleNumber] = mod.getSwervePosition();
        }
        return states;
    }

    public void zeroGyro(){
        gyro.setYaw(0);
    }
    
    public void updatePreferences(HashMap<String, String> settings) {
        SwerveSettings.deadzone = Double.parseDouble(settings.get("stick_deadband"));
        Robot.ctreConfigs.swerveDriveFXConfig.openloopRamp = Double.parseDouble(settings.get("open_ramp"));
        Robot.ctreConfigs.swerveDriveFXConfig.closedloopRamp = Double.parseDouble(settings.get("closed_ramp"));
        SwerveSettings.Swerve.maxSpeed = Double.parseDouble(settings.get("max_speed"));
        SwerveSettings.Swerve.maxAngularVelocity = Double.parseDouble(settings.get("max_angular_velocity"));
        SwerveSettings.Swerve.angleNeutralMode = Boolean.parseBoolean(settings.get("brake_angle")) ? NeutralMode.Brake : NeutralMode.Coast;
        SwerveSettings.Swerve.driveNeutralMode = Boolean.parseBoolean(settings.get("brake_drive")) ? NeutralMode.Brake : NeutralMode.Coast;

        for (SwerveModule swerve_module: mSwerveMods) {
            swerve_module.reapplyConfig();
        }
    }

    public double getChassisSpeed() {
        return chassis_speed;
    }

    public Rotation2d getYaw() {
        double[] ypr = new double[3];
        gyro.getYawPitchRoll(ypr);
        return (SwerveSettings.Swerve.invertGyro) ? Rotation2d.fromDegrees(360 - ypr[0]) : Rotation2d.fromDegrees(ypr[0]);
    }

    public void addEvent(String event_name, Command command_to_execute) {
        events.put(event_name, command_to_execute);
    }

    public Command getSoloPathCommand(PATH_LIST traj_path, boolean is_first) {
        PathPlannerTrajectory traj = trajectories.get(traj_path);
        return new SequentialCommandGroup(
            new InstantCommand(() -> {
              // Reset odometry for the first path you run during auto
              if(is_first){
                  this.resetOdometry(traj.getInitialHolonomicPose());
              }
            }),
            builder.followPathWithEvents(traj)
        );
    }

    public Command getSoloPathCommand(PATH_LIST path) {
        return builder.followPathWithEvents(trajectories.get(path));
    }

    public Command getFullAutoPath(PATH_LIST... traj) {
        ArrayList<PathPlannerTrajectory> paths = new ArrayList<PathPlannerTrajectory>();
        for (int i = 0; i < traj.length; i++) {
            paths.add(trajectories.get(traj[i]));
        } 

        SequentialCommandGroup sequential = new SequentialCommandGroup(
            new InstantCommand(() -> this.resetOdometry(paths.get(0).getInitialHolonomicPose())),
            builder.fullAuto(paths)
        );

        return sequential;
    }

    @Override
    public void periodic() {
        Pose2d previous_pose = swerveOdometry.getPoseMeters();
        swerveOdometry.update(getYaw(), getModulePositions());
        Pose2d current_pose = swerveOdometry.getPoseMeters();

        Instant now = Instant.now();
        double elapsed_time = ((double)Duration.between(last_instant, now).getNano());
        chassis_speed = Math.abs(current_pose.getTranslation().getDistance(previous_pose.getTranslation())) / (elapsed_time / 1e9);

        last_instant = now;
    }
}