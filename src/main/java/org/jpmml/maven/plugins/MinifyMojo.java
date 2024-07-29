/*
 * Copyright (c) 2024 Villu Ruusmann
 */
package org.jpmml.maven.plugins;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.vafer.jdependency.Clazz;
import org.vafer.jdependency.Clazzpath;

@Mojo (
	name = "minify",
	requiresDependencyResolution = ResolutionScope.RUNTIME
)
public class MinifyMojo extends AbstractMojo {

	@Parameter (
		defaultValue = "${project}",
		required = true,
		readonly = true
	)
	MavenProject project;

	@Parameter (
		required = true
	)
	String artifact;

	@Parameter (
		required = true
	)
	Set<String> entryPoints;

	@Parameter (
		required = true
	)
	File outputFile;


	@Override
	public void execute() throws MojoExecutionException {
		Map<?, ?> artifactMap = this.project.getArtifactMap();

		Artifact targetArtifact = (Artifact)artifactMap.get(this.artifact);
		if(targetArtifact == null){
			throw new MojoExecutionException("Failed to minify artifact: parameter artifact refers to artifact " + this.artifact + " that is not contained in project artifacts");
		}

		File targetArtifactFile = targetArtifact.getFile();

		Set<String> entryPoints = new LinkedHashSet<>(this.entryPoints);
		Set<String> expandableEntryPoints = new LinkedHashSet<>();

		for(Iterator<String> it = entryPoints.iterator(); it.hasNext(); ){
			String entryPoint = it.next();

			if(entryPoint.startsWith("META-INF") && entryPoint.endsWith(".properties")){
				it.remove();

				expandableEntryPoints.add(entryPoint);
			}
		}

		try {
			Clazzpath clazzpath = new Clazzpath();

			Artifact projectArtifact = this.project.getArtifact();

			File projectArtifactFile = projectArtifact.getFile();

			clazzpath.addClazzpathUnit(projectArtifactFile);

			if(!expandableEntryPoints.isEmpty()){
				entryPoints.addAll(expandEntryPoints(projectArtifactFile, expandableEntryPoints));
			}

			Collection<Artifact> dependencyArtifacts = (Collection<Artifact>)artifactMap.values();
			for(Artifact dependencyArtifact : dependencyArtifacts){
				File dependencyArtifactFile = dependencyArtifact.getFile();

				clazzpath.addClazzpathUnit(dependencyArtifactFile);

				if(!expandableEntryPoints.isEmpty()){
					entryPoints.addAll(expandEntryPoints(dependencyArtifactFile, expandableEntryPoints));
				}
			}

			Set<Clazz> entryPointClazzes = entryPoints.stream()
				.map(entryPoint -> clazzpath.getClazz(entryPoint))
				.collect(Collectors.toSet());

			Set<Clazz> removableClazzes = clazzpath.getClazzes();

			entryPointClazzes.stream()
				.forEach(entryPointClazz -> {
					removableClazzes.remove(entryPointClazz);

					Set<Clazz> transitiveDependencyClazzes = entryPointClazz.getTransitiveDependencies();
					if(!transitiveDependencyClazzes.isEmpty()){
						removableClazzes.removeAll(transitiveDependencyClazzes);
					}
				});

			Predicate<JarEntry> predicate = new Predicate<JarEntry>(){

				@Override
				public boolean test(JarEntry jarEntry){
					String name = jarEntry.getName();

					if(name.endsWith(".class")){
						Clazz clazz = clazzpath.getClazz(name.substring(0, name.length() - ".class".length()).replace('/', '.'));

						return !removableClazzes.contains(clazz);
					}

					return true;
				}
			};

			filterJarFile(targetArtifactFile, this.outputFile, predicate);
		} catch(Exception e){
			throw new MojoExecutionException("Failed to minify artifact", e);
		}
	}

	static
	private void filterJarFile(File inputFile, File outputFile, Predicate<JarEntry> predicate) throws IOException {

		try(JarFile jarFile = new JarFile(inputFile)){

			try(JarOutputStream jarOs = new JarOutputStream(new FileOutputStream(outputFile))){
				byte[] buffer = new byte[16 * 1024];

				for(Enumeration<JarEntry> entries = jarFile.entries(); entries.hasMoreElements(); ){
					JarEntry jarEntry = entries.nextElement();

					if(predicate.test(jarEntry)){
						jarOs.putNextEntry(jarEntry);

						try(InputStream jarIs = jarFile.getInputStream(jarEntry)){

							while(true){
								int length = jarIs.read(buffer);
								if(length < 0){
									break;
								}

								jarOs.write(buffer, 0, length);
							}
						}

						jarOs.closeEntry();
					}
				}
			}
		}
	}

	static
	private Set<String> expandEntryPoints(File file, Set<String> entryPoints) throws IOException {
		Set<String> result = new LinkedHashSet<>();

		try(JarFile jarFile = new JarFile(file)){

			for(String entryPoint : entryPoints){
				JarEntry jarEntry = (JarEntry)jarFile.getEntry(entryPoint);

				if(jarEntry == null){
					continue;
				} // End if

				if(entryPoint.startsWith("META-INF") && entryPoint.endsWith(".properties")){
					result.addAll(loadPropertyValues(jarFile, jarEntry));
				} else

				{
					throw new IllegalArgumentException(entryPoint);
				}
			}
		}

		return result;
	}

	static
	private Set<String> loadPropertyValues(JarFile jarFile, JarEntry jarEntry) throws IOException {
		Properties properties = new Properties();

		try(InputStream is = jarFile.getInputStream(jarEntry)){
			properties.load(is);
		}

		return (properties.values()).stream()
			.map(String.class::cast)
			.collect(Collectors.toSet());
	}
}